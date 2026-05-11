package com.github.wellch4n.oops.infrastructure.kubernetes.container;

import com.github.wellch4n.oops.infrastructure.kubernetes.container.clone.CloneStrategy;
import com.github.wellch4n.oops.infrastructure.kubernetes.container.clone.CloneStrategyParam;
import com.github.wellch4n.oops.infrastructure.kubernetes.container.clone.GitCloneStrategy;
import com.github.wellch4n.oops.infrastructure.kubernetes.container.clone.ZipCloneStrategy;
import com.github.wellch4n.oops.domain.application.Application;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */

public class CloneContainer extends BaseContainer {

    public CloneContainer(Application application, CloneStrategyParam strategyParam) {
        List<CloneStrategy<? extends CloneStrategyParam>> strategies = List.of(
                new GitCloneStrategy(),
                new ZipCloneStrategy()
        );
        String command = buildCommand(strategies, application, strategyParam);

        Container container = new ContainerBuilder()
                .withName("fetch")
                .withImage(strategyParam.image())
                .withCommand("sh", "-c", command)
                .addNewEnv()
                    .withName("GIT_SSH_COMMAND")
                    .withValue("ssh -i /root/.ssh/id_rsa -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR")
                .endEnv()
                .build();

        this.setName(container.getName());
        this.setImage(container.getImage());
        this.setCommand(container.getCommand());
        this.setEnv(container.getEnv());
    }

    @SuppressWarnings("unchecked")
    private static String buildCommand(List<CloneStrategy<? extends CloneStrategyParam>> strategies, Application application, CloneStrategyParam strategyParam) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(strategyParam))
                .findFirst()
                .map(strategy -> ((CloneStrategy<CloneStrategyParam>) strategy).buildCommand(application, strategyParam))
                .orElseThrow(() -> new IllegalArgumentException("Unsupported clone strategy param: " + strategyParam.getClass().getName()));
    }
}
