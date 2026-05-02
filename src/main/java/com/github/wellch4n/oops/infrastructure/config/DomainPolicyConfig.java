package com.github.wellch4n.oops.infrastructure.config;

import com.github.wellch4n.oops.domain.application.ApplicationBuildConfigPolicy;
import com.github.wellch4n.oops.domain.application.HealthCheckPolicy;
import com.github.wellch4n.oops.domain.delivery.DeployStrategyPolicy;
import com.github.wellch4n.oops.domain.delivery.DeploymentConcurrencyPolicy;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
import com.github.wellch4n.oops.domain.routing.DomainPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainPolicyConfig {

    @Bean
    public ApplicationBuildConfigPolicy applicationBuildConfigPolicy() {
        return new ApplicationBuildConfigPolicy();
    }

    @Bean
    public HealthCheckPolicy healthCheckPolicy() {
        return new HealthCheckPolicy();
    }

    @Bean
    public DeployStrategyPolicy deployStrategyPolicy() {
        return new DeployStrategyPolicy();
    }

    @Bean
    public DeploymentConcurrencyPolicy deploymentConcurrencyPolicy() {
        return new DeploymentConcurrencyPolicy();
    }

    @Bean
    public PipelineStateMachine pipelineStateMachine() {
        return PipelineStateMachine.getInstance();
    }

    @Bean
    public DomainPolicy domainPolicy() {
        return new DomainPolicy();
    }
}
