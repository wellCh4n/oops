package com.github.wellch4n.oops.config;

/**
 * @author wellCh4n
 * @date 2025/8/2
 */
public class EnvironmentContext {

    private static final ThreadLocal<String> environment = new InheritableThreadLocal<>();

    public static String getEnvironment() {
        return environment.get();
    }

    public static void setEnvironment(String environment) {
        EnvironmentContext.environment.set(environment);
    }
}
