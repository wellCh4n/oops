package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.config.FeishuConfig;
import com.github.wellch4n.oops.config.IDEConfig;
import com.github.wellch4n.oops.objects.Result;
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
    private IDEConfig ideConfig;

    @GetMapping
    public Result<FeaturesResponse> getFeatures() {
        return Result.success(new FeaturesResponse(
                feishuConfig != null && feishuConfig.isEnabled(),
                ideConfig != null
        ));
    }

    @Data
    @AllArgsConstructor
    public static class FeaturesResponse {
        private boolean feishu;
        private boolean ide;
    }
}
