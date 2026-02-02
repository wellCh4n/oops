package com.github.wellch4n.oops.application.domain.model;

import com.github.wellch4n.oops.data.BaseDataObject;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@Table(name = "application")
public class ApplicationDO extends BaseDataObject {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;

    private String description;

    private String namespace;
}
