package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.objects.NodeStatusResponse;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.NodeService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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
    public Result<List<NodeStatusResponse>> getNodes(@RequestParam String env) {
        return Result.success(nodeService.getNodes(env));
    }
}

