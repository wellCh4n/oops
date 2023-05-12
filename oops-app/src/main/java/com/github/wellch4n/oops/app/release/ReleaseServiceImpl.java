package com.github.wellch4n.oops.app.release;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.application.ApplicationService;
import com.github.wellch4n.oops.app.k8s.K8SClient;
import org.springframework.stereotype.Service;

/**
 * @author wellCh4n
 * @date 2023/2/13
 */

@Service
public class ReleaseServiceImpl implements ReleaseService {

    private final ApplicationService applicationService;
    private final K8SClient k8SClient;

    public ReleaseServiceImpl(ApplicationService applicationService, K8SClient k8SClient) {
        this.applicationService = applicationService;
        this.k8SClient = k8SClient;
    }

    @Override
    public Boolean publish(Long appId) throws Exception {
        Application application = applicationService.getById(appId);
        return k8SClient.release(application);
    }
}
