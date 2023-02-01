package com.github.wellch4n.oops.app.deploy;

import com.github.wellch4n.oops.common.objects.Result;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author wellCh4n
 * @date 2023/1/30
 */
@RestController
@RequestMapping(value = "/oops/api/deploy")
public class DeployServer {

    private final DeployService deployService;

    public DeployServer(DeployService deployService) {
        this.deployService = deployService;
    }

    @GetMapping(value = "/publish")
    public Result<Boolean> publish(@Param(value = "appId") Long appId) {
        try {
            deployService.publish(appId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return Result.success(true);
    }
}
