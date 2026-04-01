package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.enums.DeployMode;
import com.github.wellch4n.oops.enums.PipelineStatus;
import jakarta.persistence.Entity;
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

    private String namespace;

    private String applicationName;

    private PipelineStatus status;

    private String artifact;

    private String environment;

    private String branch;

    private DeployMode deployMode;

    public String getName() {
        return String.format("%s-pipeline-%s", applicationName, getId());
    }
}
