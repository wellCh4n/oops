package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.EnvironmentRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/31
 */

@Service
public class EnvironmentService {

    private final EnvironmentRepository environmentRepository;

    public EnvironmentService(EnvironmentRepository environmentRepository) {
        this.environmentRepository = environmentRepository;
    }

    public List<Environment> getEnvironments() {
        return environmentRepository.findAll();
    }
}
