package com.github.wellch4n.oops.data;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

/**
 * @author wellCh4n
 * @date 2025/7/23
 */

@Data
@Entity
public class BuildStorage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String applicationId;

    private String path;

    private String volume;
}
