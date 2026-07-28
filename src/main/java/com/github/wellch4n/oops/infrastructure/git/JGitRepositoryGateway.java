package com.github.wellch4n.oops.infrastructure.git;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wellch4n.oops.application.port.GitRepositoryGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.springframework.stereotype.Component;

/**
 * Lists remote refs straight from the OOPS process with JGit, so the UI can offer a branch picker
 * without spinning up a build pod. Credentials come from the environment, the same ones the clone
 * container receives through the {@code git-credential} secret.
 */
@Slf4j
@Component
public class JGitRepositoryGateway implements GitRepositoryGateway {

    private static final String BRANCH_REF_PREFIX = "refs/heads/";
    private static final String SSH_SCHEME_PREFIX = "ssh://";
    private static final String KEY_RESOURCE_NAME = "git-credential";
    private static final int TIMEOUT_SECONDS = 15;
    private static final long CACHE_SECONDS = 60L;

    private final Cache<String, List<String>> branchCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_SECONDS, TimeUnit.SECONDS)
            .maximumSize(500)
            .build();

    @Override
    public List<String> listRemoteBranches(Environment environment, String repositoryUrl) {
        String key = environment.getName() + "|" + repositoryUrl;
        return branchCache.get(key, ignored -> fetchRemoteBranches(environment, repositoryUrl));
    }

    private List<String> fetchRemoteBranches(Environment environment, String repositoryUrl) {
        Environment.GitCredential gitCredential = environment.getGitCredential();
        boolean overSsh = isSshUrl(repositoryUrl);
        SshdSessionFactory sshSessionFactory = overSsh ? createSshSessionFactory(gitCredential) : null;

        try {
            LsRemoteCommand command = Git.lsRemoteRepository()
                    .setRemote(repositoryUrl)
                    .setHeads(true)
                    .setTimeout(TIMEOUT_SECONDS);

            if (overSsh) {
                command.setTransportConfigCallback(transport -> {
                    if (transport instanceof SshTransport sshTransport) {
                        sshTransport.setSshSessionFactory(sshSessionFactory);
                    }
                });
            } else {
                CredentialsProvider credentialsProvider = createCredentialsProvider(gitCredential);
                if (credentialsProvider != null) {
                    command.setCredentialsProvider(credentialsProvider);
                }
            }

            Collection<Ref> refs = command.call();
            return refs.stream()
                    .map(Ref::getName)
                    .filter(refName -> refName.startsWith(BRANCH_REF_PREFIX))
                    .map(refName -> refName.substring(BRANCH_REF_PREFIX.length()))
                    .distinct()
                    .sorted()
                    .toList();
        } catch (GitAPIException exception) {
            log.warn("Failed to list remote branches of {} in environment {}",
                    repositoryUrl, environment.getName(), exception);
            throw new BizException(describeFailure(repositoryUrl, exception), exception);
        } finally {
            if (sshSessionFactory != null) {
                sshSessionFactory.close();
            }
        }
    }

    private static boolean isSshUrl(String repositoryUrl) {
        String url = repositoryUrl.trim();
        // Either the explicit ssh:// scheme or the scp-like git@host:group/repo.git form.
        return url.startsWith(SSH_SCHEME_PREFIX)
                || (!url.contains("://") && url.contains("@") && url.contains(":"));
    }

    private static CredentialsProvider createCredentialsProvider(Environment.GitCredential gitCredential) {
        if (gitCredential == null || gitCredential.isEmpty()) {
            // Public repository over HTTP(S) — anonymous access still works.
            return null;
        }
        String username = StringUtils.defaultString(gitCredential.getUsername());
        String password = StringUtils.defaultString(gitCredential.getPassword());
        if (StringUtils.isAllBlank(username, password)) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(username, password);
    }

    private static SshdSessionFactory createSshSessionFactory(Environment.GitCredential gitCredential) {
        if (gitCredential == null || StringUtils.isBlank(gitCredential.getPrivateKey())) {
            throw new BizException("The repository is cloned over SSH, "
                    + "but the environment has no git private key configured.");
        }

        List<KeyPair> keyPairs = loadKeyPairs(gitCredential.getPrivateKey());
        File sshDirectory = emptySshDirectory();

        return new SshdSessionFactoryBuilder()
                .setPreferredAuthentications("publickey")
                .setHomeDirectory(sshDirectory)
                .setSshDirectory(sshDirectory)
                .setDefaultKeysProvider(ignored -> keyPairs)
                // Mirrors the StrictHostKeyChecking=no the clone container already runs with.
                .setServerKeyDatabase((ignoredHome, ignoredSshDir) -> new AcceptAllServerKeyDatabase())
                .build(new JGitKeyCache());
    }

    private static List<KeyPair> loadKeyPairs(String privateKey) {
        // A key without a trailing newline is rejected by the parser, same as in the build pod.
        String normalizedKey = privateKey.endsWith("\n") ? privateKey : privateKey + "\n";
        try (InputStream keyStream = new ByteArrayInputStream(normalizedKey.getBytes(StandardCharsets.UTF_8))) {
            Iterable<KeyPair> identities = SecurityUtils.loadKeyPairIdentities(
                    null, NamedResource.ofName(KEY_RESOURCE_NAME), keyStream, FilePasswordProvider.EMPTY);
            List<KeyPair> keyPairs = new ArrayList<>();
            if (identities != null) {
                identities.forEach(keyPairs::add);
            }
            if (keyPairs.isEmpty()) {
                throw new BizException("No usable SSH key found in the environment git private key.");
            }
            return keyPairs;
        } catch (IOException | GeneralSecurityException exception) {
            throw new BizException("Failed to parse the environment git private key: "
                    + exception.getMessage(), exception);
        }
    }

    /**
     * An empty directory handed to JGit as $HOME and ~/.ssh, so it never picks up keys or
     * known_hosts belonging to whoever runs the OOPS process.
     */
    private static File emptySshDirectory() {
        try {
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "oops-jgit-ssh");
            Files.createDirectories(directory);
            return directory.toFile();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String describeFailure(String repositoryUrl, GitAPIException exception) {
        if (exception instanceof InvalidRemoteException) {
            return "Repository not found: " + repositoryUrl;
        }
        String reason = StringUtils.defaultString(exception.getMessage()).toLowerCase(Locale.ROOT);
        if (reason.contains("auth") || reason.contains("credential") || reason.contains("403")) {
            return "Git authentication failed, check the git credential of the environment.";
        }
        return "Failed to read branches from " + repositoryUrl + ": " + exception.getMessage();
    }

    private static final class AcceptAllServerKeyDatabase implements ServerKeyDatabase {

        @Override
        public List<PublicKey> lookup(String connectAddress,
                                      InetSocketAddress remoteAddress,
                                      Configuration config) {
            return List.of();
        }

        @Override
        public boolean accept(String connectAddress,
                              InetSocketAddress remoteAddress,
                              PublicKey serverKey,
                              Configuration config,
                              CredentialsProvider provider) {
            return true;
        }
    }
}
