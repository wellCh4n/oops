package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.FeishuService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/auth/feishu")
public class FeishuAuthController {

    private final FeishuService feishuService;

    public FeishuAuthController(FeishuService feishuService) {
        this.feishuService = feishuService;
    }

    @GetMapping("/redirect")
    public Result<String> getLoginUrl() {
        return Result.success(feishuService.getLoginUrl());
    }

    @PostMapping("/callback")
    public Result<String> callback(@RequestParam("code") String code) {
        try {
            String token = feishuService.authenticate(code);
            return Result.success(token);
        } catch (IOException e) {
            return Result.failure("飞书登录失败: " + e.getMessage());
        }
    }
}