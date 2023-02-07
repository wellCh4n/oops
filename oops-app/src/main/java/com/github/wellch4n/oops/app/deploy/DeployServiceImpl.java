package com.github.wellch4n.oops.app.deploy;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.application.ApplicationService;
import com.github.wellch4n.oops.app.application.pipe.ApplicationPipeService;
import com.github.wellch4n.oops.app.application.pipe.ApplicationPipeVertex;
import com.github.wellch4n.oops.app.k8s.K8SClient;
import com.github.wellch4n.oops.app.pipline.Pipeline;
import com.github.wellch4n.oops.app.system.SystemConfig;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2023/1/30
 */

@Service
public class DeployServiceImpl implements DeployService{

    private final ApplicationService applicationService;
    private final ApplicationPipeService applicationPipeService;
    private final K8SClient k8SClient;
    private final SystemConfig systemConfig;

    public DeployServiceImpl(ApplicationService applicationService, ApplicationPipeService applicationPipeService,
                             K8SClient k8SClient, SystemConfig systemConfig) {
        this.applicationService = applicationService;
        this.applicationPipeService = applicationPipeService;
        this.k8SClient = k8SClient;
        this.systemConfig = systemConfig;
    }

    @Override
    public void publish(Long appId) throws Exception {
        Application application = applicationService.getById(appId);
        List<ApplicationPipeVertex> applicationPipeVertices = applicationPipeService.listByApplicationId(appId);
        Pipeline pipeline = new Pipeline(applicationPipeVertices, systemConfig);
        boolean pod = k8SClient.createPod(application, pipeline);
    }
}
