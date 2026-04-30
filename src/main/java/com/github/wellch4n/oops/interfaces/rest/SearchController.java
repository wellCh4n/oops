package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.interfaces.dto.ApplicationResponse;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.ApplicationService;
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
    public Result<List<ApplicationResponse>> searchApplications(@RequestParam(required = false) String keyword, @RequestParam(defaultValue = "5") int size) {
        return Result.success(applicationService.searchApplications(keyword, size));
    }
}
