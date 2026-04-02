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

    public String getLoginUrl(String provider) {
        return getStrategy(provider).getLoginUrl();
    }

    public String authenticate(String provider, String code) throws IOException {
        return getStrategy(provider).authenticate(code);
    }

    private ExternalAuthStrategy getStrategy(String provider) {
        ExternalAuthStrategy strategy = strategies.get(provider.toLowerCase());
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported external login provider: " + provider);
        }
        return strategy;
    }
}
