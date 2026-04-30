package com.github.wellch4n.oops.application.port.external;

import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import java.io.IOException;

public interface ExternalAuthStrategy {
    ExternalAccountProvider getProvider();

    boolean isEnabled();

    String getLoginUrl();

    String authenticate(String code) throws IOException;
}
