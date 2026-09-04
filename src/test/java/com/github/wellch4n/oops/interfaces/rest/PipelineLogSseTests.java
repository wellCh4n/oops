package com.github.wellch4n.oops.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.wellch4n.oops.application.port.EventStreamSink;
import com.github.wellch4n.oops.application.service.PipelineService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The two pipeline log endpoints down to the bytes on the wire: what a gateway pushes into the
 * {@link EventStreamSink} must come out as well-formed server-sent events, including a push made
 * before Spring has taken over the response, and the browser's resume header must reach the service.
 */
class PipelineLogSseTests {

    private static final String LOG_URL = "/api/namespaces/team/applications/shop/pipelines/p1/log";
    private static final String STEPS_URL = "/api/namespaces/team/applications/shop/pipelines/p1/steps/watch";

    private PipelineService pipelineService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        pipelineService = mock(PipelineService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PipelineController(pipelineService)).build();
    }

    @Test
    void stepsWatchWritesNamedEventsAndEndsTheResponse() throws Exception {
        AutoCloseable upstream = mock(AutoCloseable.class);
        when(pipelineService.watchPipelineSteps(eq("team"), eq("shop"), eq("p1"), any())).thenAnswer(invocation -> {
            // A finished build is answered synchronously, before the controller has even returned.
            EventStreamSink sink = invocation.getArgument(3);
            sink.send("steps", null, "[\"clone\",\"push\",\"done\"]");
            sink.send("status", null, "{\"phase\":\"Succeeded\",\"steps\":[]}");
            sink.send("end", null, "{}");
            sink.close();
            return upstream;
        });

        MvcResult result = mockMvc.perform(get(STEPS_URL))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:steps\ndata:[\"clone\",\"push\",\"done\"]\n\n");
        assertThat(body).contains("event:status\ndata:{\"phase\":\"Succeeded\",\"steps\":[]}\n\n");
        assertThat(body).endsWith("event:end\ndata:{}\n\n");
        // Closing the stream releases whatever the gateway had open behind it.
        verify(upstream).close();
    }

    @Test
    void logStreamCarriesTheBatchTimeAsEventIdAndHandsTheResumeHeaderToTheService() throws Exception {
        AtomicReference<String> resumeFrom = new AtomicReference<>();
        when(pipelineService.streamPipelineStepLog(eq("team"), eq("shop"), eq("p1"), eq("push"), any(), any())).thenAnswer(invocation -> {
            resumeFrom.set(invocation.getArgument(4));
            EventStreamSink sink = invocation.getArgument(5);
            sink.send("log", "2026-09-04T02:00:11Z", "{\"lines\":[{\"time\":\"2026-09-04T02:00:11Z\",\"text\":\"pushed\"}]}");
            sink.send("end", null, "{}");
            sink.close();
            return mock(AutoCloseable.class);
        });

        MvcResult result = mockMvc.perform(get(LOG_URL)
                        .param("container", "push")
                        .header("Last-Event-ID", "2026-09-04T02:00:10Z"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(resumeFrom.get()).isEqualTo("2026-09-04T02:00:10Z");
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:log\nid:2026-09-04T02:00:11Z\ndata:{\"lines\":[{\"time\":\"2026-09-04T02:00:11Z\",\"text\":\"pushed\"}]}\n\n");
        assertThat(body).endsWith("event:end\ndata:{}\n\n");
    }

    @Test
    void logStreamWithoutAResumeHeaderPassesNull() throws Exception {
        AtomicReference<String> resumeFrom = new AtomicReference<>("unset");
        when(pipelineService.streamPipelineStepLog(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            resumeFrom.set(invocation.getArgument(4));
            EventStreamSink sink = invocation.getArgument(5);
            sink.send("end", null, "{}");
            sink.close();
            return mock(AutoCloseable.class);
        });

        mockMvc.perform(get(LOG_URL).param("container", "clone")).andExpect(status().isOk());

        assertThat(resumeFrom.get()).isNull();
    }
}
