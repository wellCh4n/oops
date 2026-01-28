package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.enums.PipelineStatus;
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
public class Pipeline extends BaseDataObject {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String namespace;

    private String applicationName;

    @Enumerated(EnumType.STRING)
    private PipelineStatus status;

    private String artifact;

    private String environment;

    public String getName() {
        return String.format("%s-pipeline-%s", applicationName, id);
    }
}
