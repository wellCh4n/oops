package com.github.wellch4n.oops.app.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipeStruct;
import com.github.wellch4n.oops.common.objects.PageResult;
import com.github.wellch4n.oops.common.objects.Result;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Set;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */

@RestController
@RequestMapping(value = "/oops/api/application")
public class ApplicationServer {
    private static Set<String> PIPE_CLAZZ_NAMES = new HashSet<>();

    static {
        PIPE_CLAZZ_NAMES.add("com.github.wellch4n.oops.pipe.DingtalkMessagePipe");
        PIPE_CLAZZ_NAMES.add("com.github.wellch4n.oops.pipe.GitPipe");
        PIPE_CLAZZ_NAMES.add("com.github.wellch4n.oops.pipe.MavenPipe");
        PIPE_CLAZZ_NAMES.add("com.github.wellch4n.oops.pipe.DockerPushPipe");
    }

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
    public PageResult<Application> page(@RequestBody ApplicationPageParam param) {
        Page<Application> page = new Page<>(param.getCurrent(), param.getPageSize());

        LambdaQueryWrapper<Application> query = new LambdaQueryWrapper<>();

        if (StringUtils.isNotEmpty(param.getNamespace())) {
            query.eq(Application::getNamespace, param.getNamespace());
        }
        if (StringUtils.isNotEmpty(param.getAppName())) {
            query.like(Application::getAppName, "%" + param.getAppName() + "%");
        }

        Page<Application> result = applicationService.page(page, query);
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    @GetMapping(value = "/pipeStruct")
    public Result<PipeStruct> pipeInputResult(@Param(value = "pipeClass") String pipeClass) {
        try {
            return Result.success(Pipe.getPipeStruct(pipeClass));
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping(value = "/pipeStructs")
    public Result<Set<PipeStruct>> pipeInputResults() {
        try {
            Set<PipeStruct> pipeStructs = new HashSet<>();
            for (String clazzName : PIPE_CLAZZ_NAMES) {
                PipeStruct pipeStruct = Pipe.getPipeStruct(clazzName);
                pipeStructs.add(pipeStruct);
            }
            return Result.success(pipeStructs);
        } catch (Exception e) {
            return null;
        }
    }

}
