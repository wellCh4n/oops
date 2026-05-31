package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.domain.shared.PipelineTriggerType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDateTime;
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

    @Enumerated(EnumType.STRING)
    private ApplicationSourceType publishType;

    private String publishRepository;

    private DeployMode deployMode;

    private String operatorId;

    private String message;

    @Enumerated(EnumType.STRING)
    private PipelineTriggerType triggerType;

    private String rollbackFromPipelineId;

    private LocalDateTime verifyDeadline;

    public String getName() {
        return String.format("%s-pipeline-%s", applicationName, getId());
    }
}
