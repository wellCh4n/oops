package com.github.wellch4n.oops.infrastructure.kubernetes.crds;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A Traefik Middleware carries exactly one middleware type, so unset kinds must not be serialized
 * as explicit nulls.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MiddlewareSpec {

    private RedirectScheme redirectScheme;
    private BasicAuth basicAuth;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RedirectScheme {
        private String scheme;
        private boolean permanent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BasicAuth {
        /** Name of a Secret in the same namespace holding htpasswd lines under the {@code users} key. */
        private String secret;
        private String realm;
    }
}
