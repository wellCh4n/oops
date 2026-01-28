package com.github.wellch4n.oops.objects;

import lombok.Data;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@Data
public class ApplicationCreateOrUpdateRequest {
    private String name;
    private String description;
    private String repository;
    private String dockerFile;
    private String buildImage;
}
