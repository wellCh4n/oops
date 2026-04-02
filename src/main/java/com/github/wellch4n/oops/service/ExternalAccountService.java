package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.service.external.ExternalAuthStrategy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    public String authenticate(String provider, String code) throws IOException {
        return getEnabledStrategy(provider).authenticate(code);
    }

    private ExternalAuthStrategy getEnabledStrategy(String provider) {
        ExternalAuthStrategy strategy = strategies.get(provider.toLowerCase());
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported external login provider: " + provider);
        }
        if (!strategy.isEnabled()) {
            throw new IllegalArgumentException("External login provider is disabled: " + provider);
        }
        return strategy;
    }
}
