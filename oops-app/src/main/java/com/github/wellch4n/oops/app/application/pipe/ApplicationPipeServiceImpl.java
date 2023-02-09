package com.github.wellch4n.oops.app.application.pipe;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2023/2/7
 */

@Service
public class ApplicationPipeServiceImpl implements ApplicationPipeService {

    private final TransactionTemplate transactionTemplate;
    private final ApplicationPipeVertexRepository vertexRepository;
    private final ApplicationPipeEdgeRepository edgeRepository;

    public ApplicationPipeServiceImpl(TransactionTemplate transactionTemplate,
                                      ApplicationPipeVertexRepository vertexRepository,
                                      ApplicationPipeEdgeRepository edgeRepository) {
        this.transactionTemplate = transactionTemplate;
        this.vertexRepository = vertexRepository;
        this.edgeRepository = edgeRepository;
    }

    @Override
    public ApplicationPipeRelation line(Long appid) {
        ApplicationPipeRelation applicationPipeRelation = new ApplicationPipeRelation();

        LambdaQueryWrapper<ApplicationPipeVertex> vertexLambdaQueryWrapper = new LambdaQueryWrapper<>();
        vertexLambdaQueryWrapper.eq(ApplicationPipeVertex::getAppId, appid);
        List<ApplicationPipeVertex> vertices = vertexRepository.selectList(vertexLambdaQueryWrapper);
        applicationPipeRelation.setVertex(vertices);

        LambdaQueryWrapper<ApplicationPipeEdge> edgeLambdaQueryWrapper = new LambdaQueryWrapper<>();
        edgeLambdaQueryWrapper.eq(ApplicationPipeEdge::getAppId, appid);
        List<ApplicationPipeEdge> edges = edgeRepository.selectList(edgeLambdaQueryWrapper);
        applicationPipeRelation.setEdges(edges);

        return applicationPipeRelation;
    }

    @Override
    public boolean put(ApplicationPipeRelation relation) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            LambdaQueryWrapper<ApplicationPipeVertex> vertexLambdaQueryWrapper = new LambdaQueryWrapper<>();
            vertexLambdaQueryWrapper.eq(ApplicationPipeVertex::getAppId, relation.getAppId());
            vertexRepository.delete(vertexLambdaQueryWrapper);

            for (ApplicationPipeVertex vertex : relation.getVertex()) {
                vertexRepository.insert(vertex);
            }

            LambdaQueryWrapper<ApplicationPipeEdge> edgeLambdaQueryWrapper = new LambdaQueryWrapper<>();
            edgeLambdaQueryWrapper.eq(ApplicationPipeEdge::getAppId, relation.getAppId());
            edgeRepository.delete(edgeLambdaQueryWrapper);

            for (ApplicationPipeEdge edge : relation.getEdges()) {
                edgeRepository.insert(edge);
            }
            return true;
        }));
    }
}
