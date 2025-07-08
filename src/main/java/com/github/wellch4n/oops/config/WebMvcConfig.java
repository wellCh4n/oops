package com.github.wellch4n.oops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final KubernetesApiClientInterceptor kubernetesApiClientInterceptor;

    public WebMvcConfig(KubernetesApiClientInterceptor kubernetesApiClientInterceptor) {
        this.kubernetesApiClientInterceptor = kubernetesApiClientInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(kubernetesApiClientInterceptor)
                .addPathPatterns("/**");
    }
}
