package com.github.wellch4n.oops.service.external;

import com.github.wellch4n.oops.enums.ExternalAccountProvider;

public interface ExternalMessageStrategy {
    ExternalAccountProvider getProvider();

    boolean isEnabled();

    void sendToUser(String userId, ExternalUserMessage message);
}
