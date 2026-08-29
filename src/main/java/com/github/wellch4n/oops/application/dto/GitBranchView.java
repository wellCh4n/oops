package com.github.wellch4n.oops.application.dto;

import java.time.Instant;

/**
 * A remote branch together with its tip commit. Everything but {@code name} and {@code commitId}
 * is best effort: when the remote refuses a shallow, blob-less fetch, the commit details stay null
 * and the UI shows the short SHA only.
 */
public record GitBranchView(String name,
                            String commitId,
                            String commitMessage,
                            String commitAuthor,
                            Instant committedAt) {

    public static GitBranchView of(String name, String commitId) {
        return new GitBranchView(name, commitId, null, null, null);
    }
}
