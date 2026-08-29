package com.github.wellch4n.oops.infrastructure.git;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wellch4n.oops.application.dto.GitBranchView;
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
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FilterSpec;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.api.TransportConfigCallback;
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

    private static final String COMMIT_FILTER_LINE = "blob:none";
    private static final int MAX_MESSAGE_LENGTH = 200;

    private final Cache<String, List<GitBranchView>> branchCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_SECONDS, TimeUnit.SECONDS)
            .maximumSize(500)
            .build();

    @Override
    public List<GitBranchView> listRemoteBranches(Environment environment, String repositoryUrl) {
        String key = environment.getName() + "|" + repositoryUrl;
        return branchCache.get(key, ignored -> fetchRemoteBranches(environment, repositoryUrl));
    }

    private List<GitBranchView> fetchRemoteBranches(Environment environment, String repositoryUrl) {
        Environment.GitCredential gitCredential = environment.getGitCredential();
        boolean overSsh = isSshUrl(repositoryUrl);
        SshdSessionFactory sshSessionFactory = overSsh ? createSshSessionFactory(gitCredential) : null;
        TransportConfigCallback transportConfig = transport -> {
            if (transport instanceof SshTransport sshTransport && sshSessionFactory != null) {
                sshTransport.setSshSessionFactory(sshSessionFactory);
            }
        };
        CredentialsProvider credentialsProvider = overSsh ? null : createCredentialsProvider(gitCredential);

        try {
            LsRemoteCommand command = Git.lsRemoteRepository()
                    .setRemote(repositoryUrl)
                    .setHeads(true)
                    .setTimeout(TIMEOUT_SECONDS)
                    .setTransportConfigCallback(transportConfig);
            if (credentialsProvider != null) {
                command.setCredentialsProvider(credentialsProvider);
            }

            Collection<Ref> refs = command.call();
            Map<String, ObjectId> tips = new HashMap<>();
            for (Ref ref : refs) {
                if (ref.getName().startsWith(BRANCH_REF_PREFIX) && ref.getObjectId() != null) {
                    tips.putIfAbsent(ref.getName().substring(BRANCH_REF_PREFIX.length()), ref.getObjectId());
                }
            }

            Map<ObjectId, RevCommit> commits = fetchTipCommits(
                    repositoryUrl, tips.values(), transportConfig, credentialsProvider);
            return tips.entrySet().stream()
                    .map(entry -> toView(entry.getKey(), entry.getValue(), commits.get(entry.getValue())))
                    .sorted((left, right) -> left.name().compareTo(right.name()))
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

    /**
     * Pulls only the tip commit of every branch into a throwaway in-memory repository: depth 1 so
     * no history comes along, and a {@code blob:none} filter so no file contents do either. This
     * is best effort — a remote that rejects shallow or partial fetches just leaves the commit
     * details empty, the branch list itself is already in hand.
     */
    private Map<ObjectId, RevCommit> fetchTipCommits(String repositoryUrl,
                                                     Collection<ObjectId> tips,
                                                     TransportConfigCallback transportConfig,
                                                     CredentialsProvider credentialsProvider) {
        Map<ObjectId, RevCommit> commits = new HashMap<>();
        if (tips.isEmpty()) {
            return commits;
        }
        List<RefSpec> refSpecs = tips.stream()
                .distinct()
                .map(tip -> new RefSpec(tip.name() + ":refs/tips/" + tip.name()))
                .toList();

        try (InMemoryRepository repository = new InMemoryRepository(new DfsRepositoryDescription("oops-branch-tips"));
             Transport transport = Transport.open(repository, new URIish(repositoryUrl))) {
            transportConfig.configure(transport);
            if (credentialsProvider != null) {
                transport.setCredentialsProvider(credentialsProvider);
            }
            transport.setTimeout(TIMEOUT_SECONDS);
            transport.setDepth(1);
            transport.setFilterSpec(FilterSpec.fromFilterLine(COMMIT_FILTER_LINE));
            transport.fetch(NullProgressMonitor.INSTANCE, refSpecs);

            try (RevWalk revWalk = new RevWalk(repository)) {
                for (ObjectId tip : tips) {
                    try {
                        commits.put(tip, revWalk.parseCommit(tip));
                    } catch (MissingObjectException exception) {
                        log.debug("Tip {} of {} was not delivered by the remote", tip.name(), repositoryUrl);
                    }
                }
            }
        } catch (Exception exception) {
            log.info("Could not read tip commits of {}, branches will carry the SHA only: {}",
                    repositoryUrl, exception.getMessage());
        }
        return commits;
    }

    private static GitBranchView toView(String name, ObjectId tip, RevCommit commit) {
        if (commit == null) {
            return GitBranchView.of(name, tip.name());
        }
        PersonIdent author = commit.getAuthorIdent();
        Instant committedAt = commit.getCommitterIdent() != null
                ? commit.getCommitterIdent().getWhenAsInstant()
                : null;
        return new GitBranchView(
                name,
                tip.name(),
                StringUtils.abbreviate(commit.getShortMessage(), MAX_MESSAGE_LENGTH),
                author != null ? author.getName() : null,
                committedAt);
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
