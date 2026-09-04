package com.github.wellch4n.oops.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The SSE endpoints complete on an ASYNC dispatch that carries no authentication: the JWT filter does
 * not run again and nothing saves the context for it. That dispatch has to be let through, or every
 * stream ends with the authorization filter denying a response that has already been sent — and a
 * chunked body cut short of its terminator. The initial dispatch stays guarded.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AsyncDispatchSecurityTests {

    private static final String STREAM = "/api/namespaces/ns/applications/app/pipelines/id/steps/watch";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void initialDispatchWithoutCredentialsIsRefused() throws Exception {
        int status = mockMvc.perform(get(STREAM)).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    @Test
    void asyncDispatchIsNotReAuthorized() throws Exception {
        int status = mockMvc.perform(get(STREAM).with(request -> {
            request.setDispatcherType(DispatcherType.ASYNC);
            return request;
        })).andReturn().getResponse().getStatus();

        // Whatever the controller answers for a pipeline that does not exist, it is the controller
        // answering — not the security chain turning the dispatch away.
        assertThat(status).isNotIn(401, 403);
    }
}
