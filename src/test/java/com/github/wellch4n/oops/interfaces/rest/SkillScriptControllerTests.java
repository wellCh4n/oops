package com.github.wellch4n.oops.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.wellch4n.oops.application.port.repository.UserRepository;
import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.infrastructure.config.OpenApiAuthFilter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class SkillScriptControllerTests {

    private static final String SCRIPT_PATH = "/openapi/skill/oops.py";
    private UserRepository userRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        userRepository = mock(UserRepository.class);
        User user = new User();
        user.setUsername("operator");
        user.setEnabled(true);
        when(userRepository.findByAccessToken("test-token")).thenReturn(Optional.of(user));
        OpenApiAuthFilter filter = new OpenApiAuthFilter(userRepository, mock(RequestMappingHandlerMapping.class));
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillScriptController()).addFilters(filter).build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sourceIsTheBundledScriptAndHashDescribesTheExactResponseBytes() throws Exception {
        byte[] source = mockMvc.perform(get(SCRIPT_PATH).header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andReturn().getResponse().getContentAsByteArray();
        try (InputStream stream = new ClassPathResource("skill/oops.py").getInputStream()) {
            assertThat(source).isEqualTo(stream.readAllBytes());
        }
        assertThat(new String(source, StandardCharsets.UTF_8)).contains("def build_parser()");
        String hash = mockMvc.perform(get(SCRIPT_PATH + "/hash").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(hash).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)));
    }

    @Test
    void bothEndpointsRequireAnEnabledUserAccessToken() throws Exception {
        User disabled = new User();
        disabled.setEnabled(false);
        when(userRepository.findByAccessToken("disabled-token")).thenReturn(Optional.of(disabled));
        for (String path : new String[]{SCRIPT_PATH, SCRIPT_PATH + "/hash"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", "Bearer disabled-token"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
