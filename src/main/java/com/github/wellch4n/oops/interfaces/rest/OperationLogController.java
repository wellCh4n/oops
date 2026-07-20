package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.OperationLogDto;
import com.github.wellch4n.oops.application.dto.Page;
import com.github.wellch4n.oops.domain.log.OperationLog;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.OperationLogService;
import com.github.wellch4n.oops.shared.log.OperationSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationLogController {

    private final OperationLogService operationLogService;

    public OperationLogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @GetMapping("/api/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Page<OperationLogDto>> queryLogs(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) OperationSource source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        org.springframework.data.domain.Page<OperationLog> logs =
            operationLogService.queryLogs(resourceType, resourceId, userId, namespace, source,
                PageRequest.of(page - 1, size));

        return Result.success(new Page<>(
            logs.getTotalElements(),
            logs.getContent().stream().map(OperationLogDto::from).toList(),
            size,
            logs.getTotalPages()
        ));
    }
}
