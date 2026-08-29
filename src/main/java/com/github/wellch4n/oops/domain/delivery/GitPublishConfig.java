package com.github.wellch4n.oops.domain.delivery;

public record GitPublishConfig(String repository, String branch) implements PublishConfig {
}
