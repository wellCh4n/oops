package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.application.dto.NodeStatusView;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.NodeService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/nodes")
public class NodeController {

    private final NodeService nodeService;

    public NodeController(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    @GetMapping
    public Result<List<NodeStatusView>> getNodes(@RequestParam String env) {
        return Result.success(nodeService.getNodes(env));
    }

    @PostMapping("/{name}/schedulable")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Boolean> setSchedulable(@PathVariable String name,
                                          @RequestParam String env,
                                          @RequestParam boolean schedulable) {
        nodeService.setSchedulable(env, name, schedulable);
        return Result.success(true);
    }
}

