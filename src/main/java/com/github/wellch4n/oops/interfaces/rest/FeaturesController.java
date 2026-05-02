package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.infrastructure.config.FeishuConfig;
import com.github.wellch4n.oops.infrastructure.config.IdeConfig;
import com.github.wellch4n.oops.infrastructure.config.BuildSourceObjectStorageConfig;
import com.github.wellch4n.oops.interfaces.dto.Result;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/features")
public class FeaturesController {

    @Autowired(required = false)
    private FeishuConfig feishuConfig;

    @Autowired(required = false)
    private IdeConfig ideConfig;

    @Autowired(required = false)
    private BuildSourceObjectStorageConfig objectStorageConfig;

    @GetMapping
    public Result<FeaturesResponse> getFeatures() {
        return Result.success(new FeaturesResponse(
                feishuConfig != null && feishuConfig.isEnabled(),
                ideConfig != null,
                ideConfig != null ? ideConfig.getDomain() : null,
                ideConfig != null && ideConfig.isHttps(),
                objectStorageConfig != null && objectStorageConfig.isEnabled()
        ));
    }

    @Data
    @AllArgsConstructor
    public static class FeaturesResponse {
        private boolean feishu;
        private boolean ide;
        private String ideHost;
        private boolean ideHttps;
        private boolean objectStorage;
    }
}
