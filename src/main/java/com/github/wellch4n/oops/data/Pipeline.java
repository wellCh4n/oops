package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.enums.DeployMode;
import com.github.wellch4n.oops.enums.ApplicationSourceType;
import com.github.wellch4n.oops.enums.PipelineStatus;
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

    private String branch;

    @Enumerated(EnumType.STRING)
    private ApplicationSourceType publishType;

    private String publishRepository;

    private DeployMode deployMode;

    private String operatorId;


    // ── 业务方法 ──

    public boolean isDeployable() {
        return PipelineStatus.BUILD_SUCCEEDED.equals(this.status);
    }

    public boolean isInFlight() {
        return PipelineStatus.RUNNING.equals(this.status)
                || PipelineStatus.DEPLOYING.equals(this.status);
    }

    public boolean isTerminal() {
        return PipelineStatus.SUCCEEDED.equals(this.status)
                || PipelineStatus.ERROR.equals(this.status)
                || PipelineStatus.STOPPED.equals(this.status);
    }

    public void transitionTo(PipelineStatus target) {
        if (!isValidTransition(this.status, target)) {
            throw new BizException("Invalid pipeline state transition: " + this.status + " → " + target);
        }
        this.status = target;
    }

    private static boolean isValidTransition(PipelineStatus from, PipelineStatus to) {
        return switch (from) {
            case INITIALIZED -> to == PipelineStatus.RUNNING;
            case RUNNING -> to == PipelineStatus.BUILD_SUCCEEDED
                    || to == PipelineStatus.DEPLOYING
                    || to == PipelineStatus.ERROR;
            case BUILD_SUCCEEDED -> to == PipelineStatus.DEPLOYING
                    || to == PipelineStatus.STOPPED;
            case DEPLOYING -> to == PipelineStatus.SUCCEEDED
                    || to == PipelineStatus.ERROR;
            default -> false;
        };
    }

    public String getName() {
        return String.format("%s-pipeline-%s", applicationName, getId());
    }
}
