package com.github.wellch4n.oops.app.deploy;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.application.ApplicationService;
import com.github.wellch4n.oops.app.k8s.K8SClient;
import com.github.wellch4n.oops.app.application.pipe.ApplicationPipe;
import com.github.wellch4n.oops.app.application.pipe.ApplicationPipeService;
import com.github.wellch4n.oops.app.pipline.Pipeline;
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

    public DeployServiceImpl(ApplicationService applicationService, ApplicationPipeService applicationPipeService,
                             K8SClient k8SClient) {
        this.applicationService = applicationService;
        this.applicationPipeService = applicationPipeService;
        this.k8SClient = k8SClient;
    }

    @Override
    public void publish(Long appId) throws Exception {
        Application application = applicationService.getById(appId);
        List<ApplicationPipe> applicationPipes = applicationPipeService.listByApplicationId(appId);
        Pipeline pipeline = new Pipeline(applicationPipes);
        boolean pod = k8SClient.createPod(application, pipeline);
    }
}
