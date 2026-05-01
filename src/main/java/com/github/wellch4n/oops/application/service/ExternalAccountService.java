package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.application.port.external.ExternalAuthStrategy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ExternalAccountService {

    private final Map<String, ExternalAuthStrategy> strategies;

    public ExternalAccountService(List<ExternalAuthStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(
                        s -> s.getProvider().name().toLowerCase(),
                        s -> s
                ));
    }

    public List<String> getEnabledProviders() {
        return strategies.values().stream()
                .filter(ExternalAuthStrategy::isEnabled)
                .map(s -> s.getProvider().name().toLowerCase())
                .toList();
    }

    public String getLoginUrl(String provider) {
        return getEnabledStrategy(provider).getLoginUrl();
    }

    public String authenticate(String provider, String code) {
        try {
            return getEnabledStrategy(provider).authenticate(code);
        } catch (IOException e) {
            throw new BizException("Authentication failed", e);
        }
    }

    private ExternalAuthStrategy getEnabledStrategy(String provider) {
        ExternalAuthStrategy strategy = strategies.get(provider.toLowerCase());
        if (strategy == null) {
            throw new BizException("Unsupported external login provider: " + provider);
        }
        if (!strategy.isEnabled()) {
            throw new BizException("External login provider is disabled: " + provider);
        }
        return strategy;
    }
}
