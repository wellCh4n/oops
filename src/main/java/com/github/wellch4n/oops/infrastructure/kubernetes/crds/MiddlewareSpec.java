package com.github.wellch4n.oops.infrastructure.kubernetes.crds;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MiddlewareSpec {

    private RedirectScheme redirectScheme;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RedirectScheme {
        private String scheme;
        private boolean permanent;
    }
}
