package com.github.wellch4n.oops.data;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

/**
 * @author wellCh4n
 * @date 2025/7/29
 */

//@Data
//@Entity
public class Environment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;

    private String apiServerUrl;

    private String apiServerToken;

    private String workNamespace;

    private String buildStorageClass;

    private String imageRepositoryUrl;
}
