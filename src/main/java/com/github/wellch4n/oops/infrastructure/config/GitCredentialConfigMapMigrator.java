package com.github.wellch4n.oops.infrastructure.config;

import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// Copies the legacy git-credential ConfigMap into a same-named Secret on startup. Remove in a future release.
@Slf4j
@Component
@Deprecated(forRemoval = true)
@RequiredArgsConstructor
public class GitCredentialConfigMapMigrator implements ApplicationRunner {

    private static final String GIT_CREDENTIAL = "git-credential";

    private final EnvironmentRepository environmentRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<Environment> environments = environmentRepository.findAll();
        log.info("[git-credential-migrator] start scanning {} environment(s) for legacy ConfigMap", environments.size());
        int scanned = 0;
        int migrated = 0;
        int skipped = 0;
        int failed = 0;
        for (Environment environment : environments) {
            scanned++;
            String envName = environment.getName();
            Environment.KubernetesApiServer apiServer = environment.getKubernetesApiServer();
            String workNamespace = environment.getWorkNamespace();
            if (apiServer == null || StringUtils.isEmpty(workNamespace)) {
                log.info("[git-credential-migrator] skip environment {} — missing apiServer or workNamespace", envName);
                skipped++;
                continue;
            }
            log.info("[git-credential-migrator] check environment {} (namespace {})", envName, workNamespace);
            try (var client = KubernetesClients.from(apiServer)) {
                if (client.secrets().inNamespace(workNamespace).withName(GIT_CREDENTIAL).get() != null) {
                    log.info("[git-credential-migrator] environment {}: Secret git-credential already exists, skip", envName);
                    skipped++;
                    continue;
                }
                ConfigMap legacy = client.configMaps().inNamespace(workNamespace).withName(GIT_CREDENTIAL).get();
                if (legacy == null) {
                    log.info("[git-credential-migrator] environment {}: no legacy ConfigMap, skip", envName);
                    skipped++;
                    continue;
                }

                LinkedHashMap<String, String> data = new LinkedHashMap<>();
                if (legacy.getData() != null) {
                    legacy.getData().forEach((key, value) ->
                            data.put(key, Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
                }
                if (legacy.getBinaryData() != null) {
                    data.putAll(legacy.getBinaryData());
                }
                if (data.isEmpty()) {
                    log.info("[git-credential-migrator] environment {}: legacy ConfigMap is empty, skip", envName);
                    skipped++;
                    continue;
                }

                log.info("[git-credential-migrator] environment {}: migrating ConfigMap keys {} to Secret", envName, data.keySet());
                var secret = new SecretBuilder()
                        .withNewMetadata()
                            .withName(GIT_CREDENTIAL)
                            .withNamespace(workNamespace)
                        .endMetadata()
                        .withType("Opaque")
                        .withData(data)
                        .build();
                client.secrets().inNamespace(workNamespace).resource(secret).patch(OopsConstants.PATCH_CONTEXT);
                migrated++;
                log.warn("[git-credential-migrator] migrated legacy git-credential ConfigMap to Secret in namespace {} (environment {}). The ConfigMap is deprecated and will be removed in a future release.",
                        workNamespace, envName);
            } catch (Exception e) {
                failed++;
                log.warn("[git-credential-migrator] environment {}: migration failed — {}", envName, e.getMessage(), e);
            }
        }
        log.info("[git-credential-migrator] done. scanned={}, migrated={}, skipped={}, failed={}",
                scanned, migrated, skipped, failed);
    }
}
