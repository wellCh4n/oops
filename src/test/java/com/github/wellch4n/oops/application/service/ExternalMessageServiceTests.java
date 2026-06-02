package com.github.wellch4n.oops.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.port.external.ExternalMessageLevel;
import com.github.wellch4n.oops.application.port.external.ExternalMessageStrategy;
import com.github.wellch4n.oops.application.port.external.ExternalUserMessage;
import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalMessageServiceTests {

    @Test
    void sendsToEnabledStrategiesOnly() {
        ExternalMessageStrategy enabled = mock(ExternalMessageStrategy.class);
        when(enabled.isEnabled()).thenReturn(true);
        when(enabled.getProvider()).thenReturn(ExternalAccountProvider.FEISHU);

        ExternalMessageService service = new ExternalMessageService(List.of(enabled));
        ExternalUserMessage message = new ExternalUserMessage("title", ExternalMessageLevel.INFO, List.of(), "detail", null);

        service.sendToUser("user-1", message);

        verify(enabled).sendToUser("user-1", message);
    }

    @Test
    void doesNothingWhenNoStrategiesEnabled() {
        ExternalMessageStrategy strategy = mock(ExternalMessageStrategy.class);
        when(strategy.isEnabled()).thenReturn(false);
        when(strategy.getProvider()).thenReturn(ExternalAccountProvider.FEISHU);

        ExternalMessageService service = new ExternalMessageService(List.of(strategy));
        ExternalUserMessage message = new ExternalUserMessage("title", ExternalMessageLevel.INFO, List.of(), "detail", null);

        service.sendToUser("user-1", message);

        verify(strategy, never()).sendToUser(any(), any());
    }
}
