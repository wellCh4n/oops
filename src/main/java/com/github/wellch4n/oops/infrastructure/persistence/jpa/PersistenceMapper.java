package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.List;

final class PersistenceMapper {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PersistenceMapper() {
    }

    static com.github.wellch4n.oops.domain.application.Application toDomain(Application entity) {
        return convert(entity, com.github.wellch4n.oops.domain.application.Application.class);
    }

    static Application toEntity(com.github.wellch4n.oops.domain.application.Application domain) {
        return convert(domain, Application.class);
    }

    static com.github.wellch4n.oops.domain.application.ApplicationBuildConfig toDomain(ApplicationBuildConfig entity) {
        return convert(entity, com.github.wellch4n.oops.domain.application.ApplicationBuildConfig.class);
    }

    static ApplicationBuildConfig toEntity(com.github.wellch4n.oops.domain.application.ApplicationBuildConfig domain) {
        return convert(domain, ApplicationBuildConfig.class);
    }

    static com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec toDomain(ApplicationRuntimeSpec entity) {
        return convert(entity, com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec.class);
    }

    static ApplicationRuntimeSpec toEntity(com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec domain) {
        return convert(domain, ApplicationRuntimeSpec.class);
    }

    static com.github.wellch4n.oops.domain.application.ApplicationEnvironment toDomain(ApplicationEnvironment entity) {
        return convert(entity, com.github.wellch4n.oops.domain.application.ApplicationEnvironment.class);
    }

    static ApplicationEnvironment toEntity(com.github.wellch4n.oops.domain.application.ApplicationEnvironment domain) {
        return convert(domain, ApplicationEnvironment.class);
    }

    static com.github.wellch4n.oops.domain.application.ApplicationServiceConfig toDomain(ApplicationServiceConfig entity) {
        return convert(entity, com.github.wellch4n.oops.domain.application.ApplicationServiceConfig.class);
    }

    static ApplicationServiceConfig toEntity(com.github.wellch4n.oops.domain.application.ApplicationServiceConfig domain) {
        return convert(domain, ApplicationServiceConfig.class);
    }

    static com.github.wellch4n.oops.domain.delivery.Pipeline toDomain(Pipeline entity) {
        return convert(entity, com.github.wellch4n.oops.domain.delivery.Pipeline.class);
    }

    static Pipeline toEntity(com.github.wellch4n.oops.domain.delivery.Pipeline domain) {
        return convert(domain, Pipeline.class);
    }

    static com.github.wellch4n.oops.domain.environment.Environment toDomain(Environment entity) {
        return convert(entity, com.github.wellch4n.oops.domain.environment.Environment.class);
    }

    static Environment toEntity(com.github.wellch4n.oops.domain.environment.Environment domain) {
        return convert(domain, Environment.class);
    }

    static com.github.wellch4n.oops.domain.routing.Domain toDomain(Domain entity) {
        return convert(entity, com.github.wellch4n.oops.domain.routing.Domain.class);
    }

    static Domain toEntity(com.github.wellch4n.oops.domain.routing.Domain domain) {
        return convert(domain, Domain.class);
    }

    static com.github.wellch4n.oops.domain.namespace.Namespace toDomain(Namespace entity) {
        return convert(entity, com.github.wellch4n.oops.domain.namespace.Namespace.class);
    }

    static Namespace toEntity(com.github.wellch4n.oops.domain.namespace.Namespace domain) {
        return convert(domain, Namespace.class);
    }

    static com.github.wellch4n.oops.domain.identity.User toDomain(User entity) {
        return convert(entity, com.github.wellch4n.oops.domain.identity.User.class);
    }

    static User toEntity(com.github.wellch4n.oops.domain.identity.User domain) {
        return convert(domain, User.class);
    }

    static com.github.wellch4n.oops.domain.identity.ExternalAccount toDomain(ExternalAccount entity) {
        return convert(entity, com.github.wellch4n.oops.domain.identity.ExternalAccount.class);
    }

    static ExternalAccount toEntity(com.github.wellch4n.oops.domain.identity.ExternalAccount domain) {
        return convert(domain, ExternalAccount.class);
    }

    private static <T> T convert(Object source, Class<T> targetType) {
        if (source == null) {
            return null;
        }
        return OBJECT_MAPPER.convertValue(source, targetType);
    }

    static <T, R> List<R> convertList(List<T> source, java.util.function.Function<T, R> mapper) {
        if (source == null) {
            return List.of();
        }
        return source.stream().map(mapper).toList();
    }
}
