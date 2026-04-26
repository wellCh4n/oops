package com.github.wellch4n.oops.config;

import com.github.wellch4n.oops.converter.DeployModeConverter;
import com.github.wellch4n.oops.converter.PipelineStatusConverter;
import com.github.wellch4n.oops.crds.IngressRoute;
import com.github.wellch4n.oops.crds.IngressRouteSpec;
import com.github.wellch4n.oops.crds.Middleware;
import com.github.wellch4n.oops.crds.MiddlewareSpec;
import com.github.wellch4n.oops.data.ApplicationBuildConfig;
import com.github.wellch4n.oops.data.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.data.ApplicationServiceConfig;
import com.github.wellch4n.oops.objects.DeployStrategyParam;
import com.github.wellch4n.oops.objects.GitDeployStrategyParam;
import com.github.wellch4n.oops.objects.ZipDeployStrategyParam;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import java.io.IOException;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

public class NativeRuntimeHints implements RuntimeHintsRegistrar {

    private static final String[] LARK_SDK_PACKAGES = {
            "com.lark.oapi.service.im.v1.model",
            "com.lark.oapi.service.im.v1.enums",
            "com.lark.oapi.service.authen.v1.model",
            "com.lark.oapi.core",
    };

    private static final String[] JJWT_PACKAGES = {
            "io.jsonwebtoken.impl",
            "io.jsonwebtoken.jackson.io",
    };

    private static final String[] FABRIC8_PACKAGES = {
            "io.fabric8.kubernetes.api.model",
            "io.fabric8.kubernetes.client",
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerJsonStoredTypes(hints);
        registerJpaConverters(hints);
        registerCrds(hints);
        registerHibernate(hints, classLoader);
        registerPackagesForReflection(hints, classLoader, LARK_SDK_PACKAGES);
        registerPackagesForReflection(hints, classLoader, JJWT_PACKAGES);
        registerPackagesForReflection(hints, classLoader, FABRIC8_PACKAGES);
        registerResources(hints);
    }

    private void registerJsonStoredTypes(RuntimeHints hints) {
        Class<?>[] types = {
                ApplicationServiceConfig.EnvironmentConfig.class,
                ApplicationBuildConfig.EnvironmentConfig.class,
                ApplicationRuntimeSpec.EnvironmentConfig.class,
                ApplicationRuntimeSpec.HealthCheck.class,
                DeployStrategyParam.class,
                GitDeployStrategyParam.class,
                ZipDeployStrategyParam.class,
        };
        for (Class<?> type : types) {
            hints.reflection().registerType(type, MemberCategory.values());
        }
    }

    private void registerJpaConverters(RuntimeHints hints) {
        Class<?>[] converters = {
                PipelineStatusConverter.class,
                DeployModeConverter.class,
                ApplicationServiceConfig.EnvironmentConfigsConverter.class,
                ApplicationBuildConfig.EnvironmentConfigsConverter.class,
                ApplicationRuntimeSpec.EnvironmentConfigsConverter.class,
                ApplicationRuntimeSpec.HealthCheckConverter.class,
        };
        for (Class<?> converter : converters) {
            hints.reflection().registerType(converter, MemberCategory.values());
        }
    }

    private void registerCrds(RuntimeHints hints) {
        Class<?>[] crds = {
                IngressRoute.class,
                IngressRouteSpec.class,
                Middleware.class,
                MiddlewareSpec.class,
                KubernetesDeserializer.class,
        };
        for (Class<?> crd : crds) {
            hints.reflection().registerType(crd, MemberCategory.values());
        }
    }

    private void registerHibernate(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerTypeIfPresent(classLoader,
                "org.hibernate.community.dialect.SQLiteDialect", MemberCategory.values());
    }

    private void registerPackagesForReflection(RuntimeHints hints, ClassLoader classLoader, String[] basePackages) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
        MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
        for (String basePackage : basePackages) {
            String pattern = "classpath*:" + basePackage.replace('.', '/') + "/**/*.class";
            try {
                Resource[] resources = resolver.getResources(pattern);
                for (Resource resource : resources) {
                    String className = metadataReaderFactory.getMetadataReader(resource)
                            .getClassMetadata().getClassName();
                    hints.reflection().registerTypeIfPresent(classLoader, className, MemberCategory.values());
                }
            } catch (IOException ignored) {
                // Package not on classpath; skip
            }
        }
    }

    private void registerResources(RuntimeHints hints) {
        hints.resources().registerPattern("ide-default-config.json");
        hints.resources().registerPattern("db/migration/sqlite/*.sql");
        hints.resources().registerPattern("db/migration/mysql/*.sql");
        hints.resources().registerPattern("org/sqlite/native/Linux/x86_64/*");
        hints.resources().registerPattern("org/sqlite/native/Linux/aarch64/*");
        hints.resources().registerPattern("org/sqlite/native/Mac/aarch64/*");
        hints.resources().registerPattern("META-INF/vertx/*");
    }
}
