package com.github.wellch4n.oops.service.external;

import com.github.wellch4n.oops.enums.ExternalAccountProvider;
import java.io.IOException;

public interface ExternalAuthStrategy {
    ExternalAccountProvider getProvider();

    boolean isEnabled();

    String getLoginUrl();

    String authenticate(String code) throws IOException;
}
