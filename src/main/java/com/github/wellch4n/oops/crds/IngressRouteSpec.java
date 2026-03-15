package com.github.wellch4n.oops.crds;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2026/3/14
 */

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngressRouteSpec {

    private List<String> entryPoints;
    private List<Route> routes;
    private Tls tls;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Route {
        private String match;
        private String kind;
        private List<Service> services;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Service {
        private String name;
        private int port;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tls {
        private String certResolver;
    }
}
