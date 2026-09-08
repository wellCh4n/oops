package com.github.wellch4n.oops.domain.shared;

public enum ApplicationSourceType {
    GIT,
    ZIP,
    /** A prebuilt image: no build job runs, the publish names the tag and the pipeline deploys it as is. */
    IMAGE
}
