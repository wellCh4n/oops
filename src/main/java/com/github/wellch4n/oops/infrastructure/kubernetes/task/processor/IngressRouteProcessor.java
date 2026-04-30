package com.github.wellch4n.oops.infrastructure.kubernetes.task.processor;

import com.github.wellch4n.oops.infrastructure.config.SpringContext;
import com.github.wellch4n.oops.infrastructure.kubernetes.crds.IngressRoute;
import com.github.wellch4n.oops.infrastructure.kubernetes.crds.IngressRouteSpec;
import com.github.wellch4n.oops.infrastructure.kubernetes.crds.Middleware;
import com.github.wellch4n.oops.infrastructure.kubernetes.crds.MiddlewareSpec;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationServiceConfig;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Domain;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.DomainRepository;
import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class IngressRouteProcessor implements DeployProcessor {

    private static final String REDIRECT_MIDDLEWARE_NAME = "oops-redirect-https";

    @Override
    public void process(DeployContext ctx) {
        String namespace = ctx.getApplication().getNamespace();
        String applicationName = ctx.getApplication().getName();

        List<ApplicationServiceConfig.EnvironmentConfig> envServiceConfigs = ctx.getApplicationServiceConfig()
                .getEnvironmentConfigs(ctx.getEnvironment().getName())
                .stream()
                .filter(c -> StringUtils.isNotEmpty(c.getHost()))
                .toList();

        if (envServiceConfigs.isEmpty()) {
            log.info("No host configured for application: {}/{} in environment: {}, skipping ingress route creation",
                    namespace, applicationName, ctx.getEnvironment().getName());
            return;
        }

        var ingressRouteCrd = ctx.getClient().apiextensions().v1().customResourceDefinitions()
                .withName(CustomResourceDefinitionContext.fromCustomResourceType(IngressRoute.class).getName())
                .get();
        if (ingressRouteCrd == null) {
            log.warn("Could not find ingress route crd");
            return;
        }

        DomainRepository domainRepository = SpringContext.getBean(DomainRepository.class);
        List<Domain> allDomains = domainRepository.findAll();

        Set<String> appliedNames = new HashSet<>();
        for (ApplicationServiceConfig.EnvironmentConfig config : envServiceConfigs) {
            String host = config.getHost();
            boolean https = Boolean.TRUE.equals(config.getHttps());

            if (https) {
                ensureRedirectMiddleware(ctx);

                String httpName = ingressRouteName(applicationName, host, "http");
                appliedNames.add(httpName);
                applyIngressRoute(ctx, httpName, host, List.of("web"), null, true);

                String httpsName = ingressRouteName(applicationName, host, "https");
                appliedNames.add(httpsName);
                applyIngressRoute(ctx, httpsName, host, List.of("websecure"),
                        buildTlsForHost(ctx, host, allDomains), false);
            } else {
                String httpName = ingressRouteName(applicationName, host, "http");
                appliedNames.add(httpName);
                applyIngressRoute(ctx, httpName, host, List.of("web"), null, false);
            }
        }

        ctx.getClient().resources(IngressRoute.class)
                .inNamespace(namespace)
                .withLabel("oops.app.name", applicationName)
                .list().getItems().stream()
                .filter(r -> !appliedNames.contains(r.getMetadata().getName()))
                .forEach(r -> ctx.getClient().resources(IngressRoute.class)
                        .inNamespace(namespace)
                        .withName(r.getMetadata().getName())
                        .delete());
    }

    private void applyIngressRoute(DeployContext ctx, String resourceName, String host,
                                   List<String> entryPoints, IngressRouteSpec.Tls tls, boolean redirect) {
        String applicationName = ctx.getApplication().getName();

        var routeBuilder = IngressRouteSpec.Route.builder()
                .match("Host(`" + host + "`)")
                .kind("Rule")
                .services(List.of(IngressRouteSpec.Service.builder().name(applicationName).port(ctx.getServicePort()).build()));
        if (redirect) {
            routeBuilder.middlewares(List.of(
                    IngressRouteSpec.Middleware.builder().name(REDIRECT_MIDDLEWARE_NAME).build()));
        }

        IngressRouteSpec spec = IngressRouteSpec.builder()
                .routes(List.of(routeBuilder.build()))
                .entryPoints(entryPoints)
                .tls(tls)
                .build();

        IngressRoute ingressRoute = new IngressRoute();
        ingressRoute.setMetadata(new ObjectMetaBuilder()
                .withName(resourceName)
                .withNamespace(ctx.getApplication().getNamespace())
                .withLabels(ctx.getLabels())
                .withOwnerReferences(ctx.getOwnerRef())
                .build());
        ingressRoute.setSpec(spec);

        try {
            ctx.getClient().resources(IngressRoute.class)
                    .inNamespace(ctx.getApplication().getNamespace())
                    .resource(ingressRoute)
                    .forceConflicts()
                    .serverSideApply();
        } catch (Exception e) {
            log.error("Error applying ingress route {}/{}: ", ctx.getApplication().getNamespace(), resourceName, e);
            throw e;
        }
    }

    private void ensureRedirectMiddleware(DeployContext ctx) {
        String namespace = ctx.getApplication().getNamespace();
        var existing = ctx.getClient().resources(Middleware.class)
                .inNamespace(namespace)
                .withName(REDIRECT_MIDDLEWARE_NAME)
                .get();
        if (existing != null) {
            return;
        }

        Middleware middleware = new Middleware();
        middleware.setMetadata(new ObjectMetaBuilder()
                .withName(REDIRECT_MIDDLEWARE_NAME)
                .withNamespace(namespace)
                .build());
        middleware.setSpec(MiddlewareSpec.builder()
                .redirectScheme(MiddlewareSpec.RedirectScheme.builder()
                        .scheme("https")
                        .permanent(true)
                        .build())
                .build());

        try {
            ctx.getClient().resources(Middleware.class)
                    .inNamespace(namespace)
                    .resource(middleware)
                    .forceConflicts()
                    .serverSideApply();
            log.info("Created redirect middleware {}/{}", namespace, REDIRECT_MIDDLEWARE_NAME);
        } catch (Exception e) {
            log.error("Error creating redirect middleware {}/{}: ", namespace, REDIRECT_MIDDLEWARE_NAME, e);
            throw e;
        }
    }

    private IngressRouteSpec.Tls buildTlsForHost(DeployContext ctx, String host, List<Domain> allDomains) {
        Domain domain = allDomains.stream()
                .filter(d -> d.getHost() != null
                        && (host.equals(d.getHost()) || host.endsWith("." + d.getHost())))
                .max((a, b) -> Integer.compare(a.getHost().length(), b.getHost().length()))
                .orElse(null);

        if (domain != null && domain.getCertMode() == DomainCertMode.UPLOADED
                && StringUtils.isNotBlank(domain.getCertPem()) && StringUtils.isNotBlank(domain.getKeyPem())) {
            syncTlsSecret(ctx, domain);
            return IngressRouteSpec.Tls.builder().secretName(tlsSecretName(domain)).build();
        }
        return IngressRouteSpec.Tls.builder().certResolver(ctx.getIngressConfig().getCertResolver()).build();
    }

    private void syncTlsSecret(DeployContext ctx, Domain domain) {
        String name = tlsSecretName(domain);
        Map<String, String> data = Map.of(
                "tls.crt", Base64.getEncoder().encodeToString(domain.getCertPem().getBytes(StandardCharsets.UTF_8)),
                "tls.key", Base64.getEncoder().encodeToString(domain.getKeyPem().getBytes(StandardCharsets.UTF_8))
        );
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(ctx.getApplication().getNamespace())
                .endMetadata()
                .withType("kubernetes.io/tls")
                .withData(data)
                .build();
        try {
            ctx.getClient().secrets().inNamespace(ctx.getApplication().getNamespace()).resource(secret).patch(ctx.getPatchContext());
        } catch (Exception e) {
            log.error("Error syncing TLS secret {}/{} for domain {}: ", ctx.getApplication().getNamespace(), name, domain.getHost(), e);
            throw e;
        }
    }

    private static String ingressRouteName(String applicationName, String host, String suffix) {
        return applicationName + "-" + suffix + "-" + host.replace('.', '-');
    }

    private static String tlsSecretName(Domain domain) {
        return "domain-" + domain.getHost().replace('.', '-');
    }
}
