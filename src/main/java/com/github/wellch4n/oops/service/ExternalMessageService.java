package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.service.external.ExternalMessageStrategy;
import com.github.wellch4n.oops.service.external.ExternalUserMessage;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ExternalMessageService {

    private final Map<String, ExternalMessageStrategy> strategies;

    public ExternalMessageService(List<ExternalMessageStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(
                        s -> s.getProvider().name().toLowerCase(),
                        s -> s
                ));
    }

    public void sendToUser(String userId, ExternalUserMessage message) {
        strategies.values().stream()
                .filter(ExternalMessageStrategy::isEnabled)
                .forEach(strategy -> strategy.sendToUser(userId, message));
    }
}
