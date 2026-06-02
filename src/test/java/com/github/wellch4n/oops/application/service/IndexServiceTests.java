package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.application.dto.ApplicationQuery;
import com.github.wellch4n.oops.application.dto.PipelineQuery;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IndexServiceTests {

    private ApplicationRepository applicationRepository;
    private PipelineRepository pipelineRepository;
    private IndexService indexService;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        pipelineRepository = mock(PipelineRepository.class);
        indexService = new IndexService(applicationRepository, pipelineRepository);
    }

    @Test
    void queryPipelinesDelegatesToRepository() {
        Pipeline pipeline = new Pipeline();
        when(pipelineRepository.query("ns", "app")).thenReturn(List.of(pipeline));

        PipelineQuery query = new PipelineQuery();
        query.setNamespace("ns");
        query.setApplicationName("app");

        List<Pipeline> result = indexService.queryPipelines(query);
        assertEquals(1, result.size());
    }

    @Test
    void queryApplicationsDelegatesToRepository() {
        Application app = new Application();
        when(applicationRepository.query("ns", "app")).thenReturn(List.of(app));

        ApplicationQuery query = new ApplicationQuery();
        query.setNamespace("ns");
        query.setName("app");

        List<Application> result = indexService.queryApplications(query);
        assertEquals(1, result.size());
    }
}
