package com.github.wellch4n.oops.event;

import com.github.wellch4n.oops.service.ExternalMessageService;
import com.github.wellch4n.oops.service.UserService;
import com.github.wellch4n.oops.service.external.ExternalMessageLevel;
import com.github.wellch4n.oops.service.external.ExternalUserFact;
import com.github.wellch4n.oops.service.external.ExternalUserMessage;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class PipelineNotificationListener {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExternalMessageService externalMessageService;
    private final UserService userService;

    public PipelineNotificationListener(ExternalMessageService externalMessageService,
                                        UserService userService) {
        this.externalMessageService = externalMessageService;
        this.userService = userService;
    }

    @EventListener
    public void onPipelineNotification(PipelineNotificationEvent event) {
        if (event == null || event.operatorId() == null || event.operatorId().isBlank()) {
            return;
        }

        externalMessageService.sendToUser(event.operatorId(), buildMessage(event));
    }

    private ExternalUserMessage buildMessage(PipelineNotificationEvent event) {
        return new ExternalUserMessage(
                "Oops 发布通知｜" + resolveTitle(event.type()),
                resolveLevel(event.type()),
                List.of(
                        new ExternalUserFact("操作人", resolveOperatorName(event.operatorId())),
                        new ExternalUserFact("应用", event.namespace() + "/" + event.applicationName()),
                        new ExternalUserFact("环境", valueOrDash(event.environment())),
                        new ExternalUserFact("分支", valueOrDash(event.branch())),
                        new ExternalUserFact("模式", event.deployMode() == null ? "-" : event.deployMode().name()),
                        new ExternalUserFact("流水线", valueOrDash(event.pipelineId())),
                        new ExternalUserFact("时间", event.createdTime() == null ? "-" : event.createdTime().format(TIME_FORMATTER))
                ),
                event.detail(),
                blankToNull(event.artifact())
        );
    }

    private String resolveOperatorName(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            return "-";
        }
        return userService.findById(operatorId)
                .map(user -> user.getUsername() == null || user.getUsername().isBlank() ? operatorId : user.getUsername())
                .orElse(operatorId);
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String resolveTitle(PipelineNotificationType type) {
        return switch (type) {
            case CREATED -> "发布任务已创建";
            case BUILD_SUCCEEDED -> "构建成功";
            case DEPLOYING -> "开始部署";
            case SUCCEEDED -> "发布成功";
            case FAILED -> "发布失败";
            case STOPPED -> "发布已停止";
        };
    }

    private ExternalMessageLevel resolveLevel(PipelineNotificationType type) {
        return switch (type) {
            case SUCCEEDED -> ExternalMessageLevel.SUCCESS;
            case FAILED -> ExternalMessageLevel.ERROR;
            case BUILD_SUCCEEDED -> ExternalMessageLevel.WARNING;
            case STOPPED -> ExternalMessageLevel.NEUTRAL;
            case CREATED, DEPLOYING -> ExternalMessageLevel.INFO;
        };
    }
}
