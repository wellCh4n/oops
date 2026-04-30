package com.github.wellch4n.oops.infrastructure.config;

import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;

public final class OopsConstants {
    private OopsConstants() {}

    public static final PatchContext PATCH_CONTEXT = new PatchContext.Builder()
            .withPatchType(PatchType.SERVER_SIDE_APPLY)
            .withFieldManager("oops")
            .withForce(true)
            .build();
}
