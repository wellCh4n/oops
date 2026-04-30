package com.github.wellch4n.oops.application.port.external;

import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;

public interface ExternalMessageStrategy {
    ExternalAccountProvider getProvider();

    boolean isEnabled();

    void sendToUser(String userId, ExternalUserMessage message);
}
