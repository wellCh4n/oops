package com.github.wellch4n.oops.shared.log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods whose operations should be logged.
 * When applied to a method, the OperationLogAspect will automatically record the operation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Loggable {

    /**
     * Operation name (e.g., "CREATE_APP", "DEPLOY", "DELETE_APP")
     */
    String operation();

    /**
     * Resource type being operated on (e.g., "Application", "Pipeline", "Environment")
     */
    String resourceType();

    /**
     * Whether to include request body in operation log details (default: false for security)
     */
    boolean includeDetails() default false;
}
