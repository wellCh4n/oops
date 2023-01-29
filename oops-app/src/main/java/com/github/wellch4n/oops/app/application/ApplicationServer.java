package com.github.wellch4n.oops.app.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.wellch4n.oops.common.objects.PageResult;
import com.github.wellch4n.oops.common.objects.Result;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */

@RestController
@RequestMapping(value = "/api/application")
public class ApplicationServer {

    private final ApplicationService applicationService;

    public ApplicationServer(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping(value = "/create")
    public Result<Boolean> create(@RequestBody Application application) {
        return Result.success(applicationService.save(application));
    }

    @GetMapping(value = "/delete")
    public Result<Boolean> delete(@Param(value = "id") Long id) {
        return Result.success(applicationService.removeById(id));
    }

    @GetMapping(value = "/detail")
    public Result<Application> detail(@Param(value = "id") Long id) {
        return Result.success(applicationService.getById(id));
    }

    @PostMapping(value = "/update")
    public Result<Boolean> update(@RequestBody Application application) {
        return Result.success(applicationService.updateById(application));
    }

    @PostMapping(value = "/page")
    public Result<PageResult<Application>> page(@RequestBody ApplicationPageParam param) {
        Page<Application> page = new Page<>(param.getCurrent(), param.getSize());
        Page<Application> result = applicationService.page(page);
        PageResult<Application> pageResult = new PageResult<>(result.getRecords(), result.getTotal(),
                                                              result.getCurrent(), result.getSize());
        return Result.success(pageResult);
    }

}
