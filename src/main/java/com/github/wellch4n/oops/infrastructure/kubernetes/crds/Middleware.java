package com.github.wellch4n.oops.infrastructure.kubernetes.crds;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Version;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Group("traefik.io")
@Version("v1alpha1")
@Kind("Middleware")
public class Middleware extends CustomResource<MiddlewareSpec, Void> implements Namespaced {
}
