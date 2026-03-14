package com.github.wellch4n.oops.crds;

import com.github.wellch4n.oops.crd.IngressRoute;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2026/3/14
 */

@Data
@Builder
public class IngressRouteSpec {

    private List<String> entryPoints;
    private List<Route> routes;
    private Tls tls;

    @Data
    @Builder
    public static class Route {
        private String match;
        private String kind;
        private List<Service> services;
    }

    @Data
    @Builder
    public static class Service {
        private String name;
        private int port;
    }

    @Data
    @Builder
    public static class Tls {
        private String certResolver;
    }
}
