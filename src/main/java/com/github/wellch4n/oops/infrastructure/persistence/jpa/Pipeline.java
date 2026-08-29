package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.domain.delivery.PublishConfig;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.domain.shared.PipelineTriggerType;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.converter.PublishConfigConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Enumerated(EnumType.STRING)
    private ApplicationSourceType publishType;

    @Column(name = "publish_config", columnDefinition = "TEXT")
    @Convert(converter = PublishConfigConverter.class)
    private PublishConfig publishConfig;

    private DeployMode deployMode;

    private String operatorId;

    private String message;

    @Enumerated(EnumType.STRING)
    private PipelineTriggerType triggerType;

    private String rollbackFromPipelineId;

    public String getName() {
        return String.format("%s-pipeline-%s", applicationName, getId());
    }
}
