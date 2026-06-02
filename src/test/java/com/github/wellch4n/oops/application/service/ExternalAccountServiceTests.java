package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import com.github.wellch4n.oops.application.port.external.ExternalAuthStrategy;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalAccountServiceTests {

    private ExternalAuthStrategy feishuStrategy() throws IOException {
        ExternalAuthStrategy strategy = mock(ExternalAuthStrategy.class);
        when(strategy.getProvider()).thenReturn(ExternalAccountProvider.FEISHU);
        when(strategy.isEnabled()).thenReturn(true);
        when(strategy.getLoginUrl()).thenReturn("https://feishu.cn/oauth");
        when(strategy.authenticate("code123")).thenReturn("user-id-1");
        return strategy;
    }

    @Test
    void getEnabledProvidersReturnsEnabledOnes() throws IOException {
        ExternalAuthStrategy strategy = feishuStrategy();
        ExternalAccountService service = new ExternalAccountService(List.of(strategy));
        List<String> providers = service.getEnabledProviders();
        assertTrue(providers.contains("feishu"));
    }

    @Test
    void getLoginUrlReturnsUrl() throws IOException {
        ExternalAccountService service = new ExternalAccountService(List.of(feishuStrategy()));
        assertEquals("https://feishu.cn/oauth", service.getLoginUrl("feishu"));
    }

    @Test
    void getLoginUrlThrowsForUnknownProvider() {
        ExternalAccountService service = new ExternalAccountService(List.of());
        assertThrows(BizException.class, () -> service.getLoginUrl("unknown"));
    }

    @Test
    void authenticateReturnsUserId() throws IOException {
        ExternalAccountService service = new ExternalAccountService(List.of(feishuStrategy()));
        assertEquals("user-id-1", service.authenticate("feishu", "code123"));
    }

    @Test
    void authenticateThrowsBizExceptionOnIOException() throws IOException {
        ExternalAuthStrategy strategy = mock(ExternalAuthStrategy.class);
        when(strategy.getProvider()).thenReturn(ExternalAccountProvider.FEISHU);
        when(strategy.isEnabled()).thenReturn(true);
        when(strategy.authenticate("bad")).thenThrow(new IOException("network error"));

        ExternalAccountService service = new ExternalAccountService(List.of(strategy));
        assertThrows(BizException.class, () -> service.authenticate("feishu", "bad"));
    }

    @Test
    void getLoginUrlThrowsWhenProviderDisabled() throws IOException {
        ExternalAuthStrategy strategy = mock(ExternalAuthStrategy.class);
        when(strategy.getProvider()).thenReturn(ExternalAccountProvider.FEISHU);
        when(strategy.isEnabled()).thenReturn(false);

        ExternalAccountService service = new ExternalAccountService(List.of(strategy));
        assertThrows(BizException.class, () -> service.getLoginUrl("feishu"));
    }
}
