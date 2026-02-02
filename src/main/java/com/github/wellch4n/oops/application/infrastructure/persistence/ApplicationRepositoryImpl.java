package com.github.wellch4n.oops.application.infrastructure.persistence;

import com.github.wellch4n.oops.application.domain.repository.ApplicationRepository;
import org.springframework.stereotype.Repository;

@Repository
public class ApplicationRepositoryImpl implements ApplicationRepository {

    private final JpaApplicationRepository applicationRepository;

    public ApplicationRepositoryImpl(JpaApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }
}
