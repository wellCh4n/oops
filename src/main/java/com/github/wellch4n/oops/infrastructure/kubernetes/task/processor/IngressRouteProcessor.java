package com.github.wellch4n.oops.infrastructure.kubernetes.task.processor;

import com.github.wellch4n.oops.infrastructure.config.SpringContext;
import com.github.wellch4n.oops.infrastructure.kubernetes.crds.IngressRoute;
import com.github.wellch4n.oops.infrastructure.kubernetes.crds.IngressRouteSpec;
import com.github.wellch4n.oops.infrastructure.kubernetes.crds.Middleware;
import com.github.wellch4n.oops.infrastructure.kubernetes.crds.MiddlewareSpec;
import com.github.wellch4n.oops.application.port.repository.DomainRepository;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.routing.Domain;
import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class IngressRouteProcessor implements DeployProcessor {

    private static final String REDIRECT_MIDDLEWARE_NAME = "oops-redirect-https";
    private static final int TLS_SECRET_SYNC_ATTEMPTS = 3;
    private static final String BASIC_AUTH_LABEL_KEY = "oops.resource";
    private static final String BASIC_AUTH_LABEL_VALUE = "basic-auth";

    @Override
    public void process(DeployContext ctx) {
        String namespace = ctx.getApplication().getNamespace();
        String applicationName = ctx.getApplication().getName();

        List<ApplicationServiceConfig.EnvironmentConfig> envServiceConfigs = ctx.getApplicationServiceConfig()
                .getEnvironmentConfigs(ctx.getEnvironment().getName())
                .stream()
                .filter(config -> StringUtils.isNotEmpty(config.getHost()))
                .toList();

        var ingressRouteCrd = ctx.getClient().apiextensions().v1().customResourceDefinitions()
                .withName(CustomResourceDefinitionContext.fromCustomResourceType(IngressRoute.class).getName())
                .get();
        if (ingressRouteCrd == null) {
            log.warn("Could not find ingress route crd");
            return;
        }

        // An application with no host still has to reach the pruning below. Returning
        // early on an empty list would mean clearing the last host leaves its routes,
        // its redirect and its basic auth credentials serving in the cluster, so the
        // owner takes a hostname down and it keeps answering.
        if (envServiceConfigs.isEmpty()) {
            log.info("No host configured for application: {}/{} in environment: {}, withdrawing any ingress routes",
                    namespace, applicationName, ctx.getEnvironment().getName());
        }

        DomainRepository domainRepository = SpringContext.getBean(DomainRepository.class);
        List<Domain> allDomains = domainRepository.findAll();

        Set<String> appliedNames = new HashSet<>();
        Set<String> appliedBasicAuthNames = new HashSet<>();
        for (ApplicationServiceConfig.EnvironmentConfig config : envServiceConfigs) {
            String host = config.getHost();
            boolean https = Boolean.TRUE.equals(config.getHttps());

            // The HTTP route of an HTTPS host only redirects, so basic auth belongs on the route
            // that actually serves the application.
            List<String> serveMiddlewares = List.of();
            if (config.basicAuthConfigured()) {
                String basicAuthName = basicAuthResourceName(applicationName, host);
                ensureBasicAuthMiddleware(ctx, basicAuthName, config);
                appliedBasicAuthNames.add(basicAuthName);
                serveMiddlewares = List.of(basicAuthName);
            }

            if (https) {
                ensureRedirectMiddleware(ctx);

                String httpName = ingressRouteName(applicationName, host, "http");
                appliedNames.add(httpName);
                applyIngressRoute(ctx, httpName, host, List.of("web"), null, List.of(REDIRECT_MIDDLEWARE_NAME));

                String httpsName = ingressRouteName(applicationName, host, "https");
                appliedNames.add(httpsName);
                applyIngressRoute(ctx, httpsName, host, List.of("websecure"),
                        buildTlsForHost(ctx, host, allDomains), serveMiddlewares);
            } else {
                String httpName = ingressRouteName(applicationName, host, "http");
                appliedNames.add(httpName);
                applyIngressRoute(ctx, httpName, host, List.of("web"), null, serveMiddlewares);
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

        deleteStaleBasicAuthResources(ctx, appliedBasicAuthNames);
    }

    private void applyIngressRoute(DeployContext ctx, String resourceName, String host,
                                   List<String> entryPoints, IngressRouteSpec.Tls tls,
                                   List<String> middlewareNames) {
        String applicationName = ctx.getApplication().getName();

        var routeBuilder = IngressRouteSpec.Route.builder()
                .match("Host(`" + host + "`)")
                .kind("Rule")
                .services(List.of(IngressRouteSpec.Service.builder().name(applicationName).port(ctx.getServicePort()).build()));
        if (!middlewareNames.isEmpty()) {
            routeBuilder.middlewares(middlewareNames.stream()
                    .map(middlewareName -> IngressRouteSpec.Middleware.builder().name(middlewareName).build())
                    .toList());
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

    /**
     * Writes the htpasswd Secret and the Traefik BasicAuth Middleware for one host. The stored
     * BCrypt hash goes straight into the Secret — Traefik reads htpasswd lines, so no plaintext is
     * needed anywhere in the cluster.
     */
    private void ensureBasicAuthMiddleware(DeployContext ctx, String resourceName,
                                           ApplicationServiceConfig.EnvironmentConfig config) {
        String namespace = ctx.getApplication().getNamespace();
        Map<String, String> labels = new HashMap<>(ctx.getLabels());
        labels.put(BASIC_AUTH_LABEL_KEY, BASIC_AUTH_LABEL_VALUE);

        String htpasswd = config.getBasicAuthUsername() + ":" + config.getBasicAuthPasswordHash();
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                    .withName(resourceName)
                    .withNamespace(namespace)
                    .withLabels(labels)
                    .withOwnerReferences(ctx.getOwnerRef())
                .endMetadata()
                .withType("Opaque")
                .withData(Map.of("users",
                        Base64.getEncoder().encodeToString(htpasswd.getBytes(StandardCharsets.UTF_8))))
                .build();

        Middleware middleware = new Middleware();
        middleware.setMetadata(new ObjectMetaBuilder()
                .withName(resourceName)
                .withNamespace(namespace)
                .withLabels(labels)
                .withOwnerReferences(ctx.getOwnerRef())
                .build());
        middleware.setSpec(MiddlewareSpec.builder()
                .basicAuth(MiddlewareSpec.BasicAuth.builder().secret(resourceName).build())
                .build());

        try {
            ctx.getClient().secrets().inNamespace(namespace).resource(secret).patch(ctx.getPatchContext());
            ctx.getClient().resources(Middleware.class)
                    .inNamespace(namespace)
                    .resource(middleware)
                    .forceConflicts()
                    .serverSideApply();
        } catch (Exception e) {
            log.error("Error applying basic auth middleware {}/{}: ", namespace, resourceName, e);
            throw e;
        }
    }

    /**
     * Drops the basic auth Secret and Middleware of hosts that no longer use it. Owner references
     * only clean up when the whole application goes away, so turning basic auth off for a single
     * host has to be handled here.
     */
    private void deleteStaleBasicAuthResources(DeployContext ctx, Set<String> appliedNames) {
        String namespace = ctx.getApplication().getNamespace();
        String applicationName = ctx.getApplication().getName();

        ctx.getClient().resources(Middleware.class)
                .inNamespace(namespace)
                .withLabel("oops.app.name", applicationName)
                .withLabel(BASIC_AUTH_LABEL_KEY, BASIC_AUTH_LABEL_VALUE)
                .list().getItems().stream()
                .map(middleware -> middleware.getMetadata().getName())
                .filter(name -> !appliedNames.contains(name))
                .forEach(name -> {
                    ctx.getClient().resources(Middleware.class).inNamespace(namespace).withName(name).delete();
                    ctx.getClient().secrets().inNamespace(namespace).withName(name).delete();
                });
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
        Exception lastError = null;
        for (int attempt = 1; attempt <= TLS_SECRET_SYNC_ATTEMPTS; attempt++) {
            try {
                ctx.getClient().secrets().inNamespace(ctx.getApplication().getNamespace()).resource(secret).patch(ctx.getPatchContext());
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("TLS secret sync attempt {}/{} failed for {}/{} domain {}",
                        attempt, TLS_SECRET_SYNC_ATTEMPTS, ctx.getApplication().getNamespace(), name, domain.getHost(), e);
            }
        }
        throw new IllegalStateException("Failed to sync TLS secret " + ctx.getApplication().getNamespace() + "/" + name,
                lastError);
    }

    private static String ingressRouteName(String applicationName, String host, String suffix) {
        return applicationName + "-" + suffix + "-" + host.replace('.', '-');
    }

    private static String basicAuthResourceName(String applicationName, String host) {
        return applicationName + "-basic-auth-" + host.replace('.', '-');
    }

    private static String tlsSecretName(Domain domain) {
        return "domain-" + domain.getHost().replace('.', '-');
    }
}
