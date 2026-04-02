package com.github.wellch4n.oops.crds;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author wellCh4n
 * @date 2026/3/14
 */

@EqualsAndHashCode(callSuper = true)
@Group("traefik.io")
@Version("v1alpha1")
@Kind("IngressRoute")
@Data
public class IngressRoute extends CustomResource<IngressRouteSpec, Void> implements Namespaced {
}
