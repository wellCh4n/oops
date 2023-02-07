package com.github.wellch4n.oops.app.application.pipe;

import com.github.wellch4n.oops.common.objects.Result;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author wellCh4n
 * @date 2023/2/7
 */

@RestController
@RequestMapping(value = "/oops/api/application/pipe")
public class ApplicationPipeServer {

    private final ApplicationPipeService applicationPipeService;

    public ApplicationPipeServer(ApplicationPipeService applicationPipeService) {
        this.applicationPipeService = applicationPipeService;
    }

    @GetMapping(value = "/line")
    public Result<ApplicationPipeRelation> line(@Param(value = "id") Long id) {
        return Result.success(applicationPipeService.line(id));
    }

    @PostMapping(value = "/put")
    public Result<Boolean> put(@RequestBody ApplicationPipeRelation relation) {
        return Result.success(applicationPipeService.put(relation));
    }
}
