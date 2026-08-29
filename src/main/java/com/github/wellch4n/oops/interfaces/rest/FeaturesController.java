package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.FeaturesView;
import com.github.wellch4n.oops.application.service.FeatureService;
import com.github.wellch4n.oops.interfaces.dto.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/features")
public class FeaturesController {

    private final FeatureService featureService;

    public FeaturesController(FeatureService featureService) {
        this.featureService = featureService;
    }

    @GetMapping
    public Result<FeaturesView> getFeatures() {
        return Result.success(featureService.getFeatures());
    }
}
