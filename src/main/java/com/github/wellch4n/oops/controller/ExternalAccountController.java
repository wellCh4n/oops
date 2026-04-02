package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.ExternalAccountService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/auth/external")
public class ExternalAccountController {

    private final ExternalAccountService externalAccountService;

    public ExternalAccountController(ExternalAccountService externalAccountService) {
        this.externalAccountService = externalAccountService;
    }

    @GetMapping("/{provider}/redirect")
    public Result<String> getLoginUrl(@PathVariable String provider) {
        return Result.success(externalAccountService.getLoginUrl(provider));
    }

    @PostMapping("/{provider}/callback")
    public Result<String> callback(@PathVariable String provider, @RequestParam("code") String code) {
        try {
            String token = externalAccountService.authenticate(provider, code);
            return Result.success(token);
        } catch (IOException e) {
            return Result.failure("登录失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }
}
