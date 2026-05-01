package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.DomainRequest;
import com.github.wellch4n.oops.interfaces.dto.DomainResponse;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.DomainService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/domains")
public class DomainController {

    private final DomainService domainService;

    public DomainController(DomainService domainService) {
        this.domainService = domainService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Result<List<DomainResponse>> list() {
        return Result.success(domainService.list().stream().map(DomainResponse::from).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Result<DomainResponse> get(@PathVariable String id) {
        return Result.success(DomainResponse.from(domainService.get(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DomainResponse> create(@RequestBody DomainRequest request) {
        return Result.success(DomainResponse.from(domainService.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DomainResponse> update(@PathVariable String id, @RequestBody DomainRequest request) {
        return Result.success(DomainResponse.from(domainService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Boolean> delete(@PathVariable String id) {
        domainService.delete(id);
        return Result.success(true);
    }
}
