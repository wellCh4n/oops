package com.github.wellch4n.oops.data;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
public class Application extends BaseDataObject {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;

    private String namespace;

    private String repository;

    private String dockerFile;

    private String buildImage;

    private String buildCommand;
}
