package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.ApplicationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author wellCh4n
 * @date 2026/4/9
 */

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final ApplicationService applicationService;

    public SearchController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping("/applications")
    public Result<List<Application>> searchApplications(@RequestParam(required = false) String keyword, @RequestParam(defaultValue = "5") int size) {
        return Result.success(applicationService.searchApplications(keyword, size));
    }
}
