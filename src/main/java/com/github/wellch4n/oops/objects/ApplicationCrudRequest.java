package com.github.wellch4n.oops.objects;

import lombok.Data;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@Data
public class ApplicationCrudRequest {
    private String name;
    private String repository;
    private String dockerFile;
    private String buildImage;
    private String buildCommand;
}
