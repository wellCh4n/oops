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
import com.github.wellch4n.oops.application.service.ApplicationService;
import com.github.wellch4n.oops.application.service.GitBranchService;
import com.github.wellch4n.oops.application.service.NamespaceMigrationService;
import com.github.wellch4n.oops.application.service.PipelineService;
import com.github.wellch4n.oops.application.service.PodMetricHistoryService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The pod log endpoint down to the bytes on the wire: it is a server-sent event stream like the
 * pipeline step log, carries the batch time as the event id, and hands the browser's resume header
 * to the service.
 */
class PodLogSseTests {

    private static final String LOG_URL = "/api/namespaces/team/applications/shop/pods/shop-0/log";

    private ApplicationService applicationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        applicationService = mock(ApplicationService.class);
        ApplicationController controller = new ApplicationController(
                applicationService,
                mock(PipelineService.class),
                mock(NamespaceMigrationService.class),
                mock(PodMetricHistoryService.class),
                mock(GitBranchService.class)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void logStreamWritesNamedEventsWithTheBatchTimeAsIdAndEndsTheResponse() throws Exception {
        AutoCloseable upstream = mock(AutoCloseable.class);
        AtomicReference<String> resumeFrom = new AtomicReference<>("unset");
        when(applicationService.streamPodLog(eq("team"), eq("shop-0"), eq("prod"), any(), any())).thenAnswer(invocation -> {
            resumeFrom.set(invocation.getArgument(3));
            // A container that has already exited is answered synchronously, before the controller
            // has even returned.
            EventStreamSink sink = invocation.getArgument(4);
            sink.send("log", "2026-09-06T02:00:11Z", "{\"lines\":[{\"time\":\"2026-09-06T02:00:11Z\",\"text\":\"listening on :8080\"}]}");
            sink.send("end", null, "{}");
            sink.close();
            return upstream;
        });

        MvcResult result = mockMvc.perform(get(LOG_URL).param("environment", "prod"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(resumeFrom.get()).isNull();
        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:log\nid:2026-09-06T02:00:11Z\ndata:{\"lines\":[{\"time\":\"2026-09-06T02:00:11Z\",\"text\":\"listening on :8080\"}]}\n\n");
        assertThat(body).endsWith("event:end\ndata:{}\n\n");
        // Closing the stream releases whatever the gateway had open behind it.
        verify(upstream).close();
    }

    @Test
    void logStreamHandsTheResumeHeaderToTheService() throws Exception {
        AtomicReference<String> resumeFrom = new AtomicReference<>();
        when(applicationService.streamPodLog(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            resumeFrom.set(invocation.getArgument(3));
            EventStreamSink sink = invocation.getArgument(4);
            sink.send("end", null, "{}");
            sink.close();
            return mock(AutoCloseable.class);
        });

        mockMvc.perform(get(LOG_URL)
                        .param("environment", "prod")
                        .header("Last-Event-ID", "2026-09-06T02:00:10Z"))
                .andExpect(status().isOk());

        assertThat(resumeFrom.get()).isEqualTo("2026-09-06T02:00:10Z");
    }
}
