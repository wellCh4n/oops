package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.EnvironmentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author wellCh4n
 * @date 2025/7/31
 */
@RestController
@RequestMapping("/api/image-repositories")
public class ImageRepositoryController {

    private final EnvironmentService environmentService;

    public ImageRepositoryController(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @PostMapping("/validations")
    public Result<Boolean> validate(@RequestBody Environment.ImageRepository imageRepository) {
        return Result.success(environmentService.validateImageRepository(imageRepository));
    }
}
