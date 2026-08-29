package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.ExternalAccountService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/external")
public class ExternalAccountController {

    private final ExternalAccountService externalAccountService;

    public ExternalAccountController(ExternalAccountService externalAccountService) {
        this.externalAccountService = externalAccountService;
    }

    @GetMapping("/providers")
    public Result<List<String>> getEnabledProviders() {
        return Result.success(externalAccountService.getEnabledProviders());
    }

    @GetMapping("/{provider}/redirect")
    public Result<String> getLoginUrl(@PathVariable String provider) {
        return Result.success(externalAccountService.getLoginUrl(provider));
    }

    @PostMapping("/{provider}/callback")
    public Result<String> callback(@PathVariable String provider, @RequestParam("code") String code) {
        return Result.success(externalAccountService.authenticate(provider, code));
    }
}
