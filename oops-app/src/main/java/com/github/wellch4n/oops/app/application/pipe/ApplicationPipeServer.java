package com.github.wellch4n.oops.app.application.pipe;

import com.github.wellch4n.oops.common.objects.Result;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2023/1/31
 */

@RestController
@RequestMapping(value = "/oops/api/application/pipe")
public class ApplicationPipeServer {

    private final ApplicationPipeService applicationPipeService;

    public ApplicationPipeServer(ApplicationPipeService applicationPipeService) {
        this.applicationPipeService = applicationPipeService;
    }

    @GetMapping(value = "/line")
    public Result<List<ApplicationPipe>> line(@Param(value = "id") Long id) {
        return Result.success(applicationPipeService.listByApplicationId(id));
    }

    @PostMapping(value = "/put")
    public Result<Boolean> put() {

    }
}
