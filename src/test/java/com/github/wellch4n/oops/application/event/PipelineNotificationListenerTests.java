package com.github.wellch4n.oops.application.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.port.external.ExternalUserMessage;
import com.github.wellch4n.oops.application.service.ExternalMessageService;
import com.github.wellch4n.oops.application.service.UserService;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PipelineNotificationListenerTests {

    private ExternalMessageService externalMessageService;
    private UserService userService;
    private PipelineNotificationListener listener;

    @BeforeEach
    void setUp() {
        externalMessageService = mock(ExternalMessageService.class);
        userService = mock(UserService.class);
        listener = new PipelineNotificationListener(externalMessageService, userService);
    }

    private Pipeline pipeline(String operatorId) {
        Pipeline pipeline = Pipeline.initialize("ns", "app", "prod", ApplicationSourceType.GIT, DeployMode.IMMEDIATE, operatorId);
        pipeline.setId("pipe-1");
        return pipeline;
    }

    @Test
    void skipsNotificationWhenEventIsNull() {
        listener.onPipelineNotification(null);
        verify(externalMessageService, never()).sendToUser(any(), any());
    }

    @Test
    void skipsNotificationWhenOperatorIdIsBlank() {
        Pipeline pipeline = pipeline(null);
        PipelineNotificationEvent event = PipelineNotificationEvent.of(pipeline, PipelineNotificationType.CREATED, "detail");
        listener.onPipelineNotification(event);
        verify(externalMessageService, never()).sendToUser(any(), any());
    }

    @Test
    void sendsNotificationWithResolvedOperatorName() {
        Pipeline pipeline = pipeline("user-1");
        User user = new User();
        user.setUsername("alice");
        when(userService.findById("user-1")).thenReturn(Optional.of(user));

        PipelineNotificationEvent event = PipelineNotificationEvent.of(pipeline, PipelineNotificationType.CREATED, "detail");
        listener.onPipelineNotification(event);

        verify(externalMessageService).sendToUser(eq("user-1"), any(ExternalUserMessage.class));
    }

    @Test
    void sendsNotificationForAllNotificationTypes() {
        Pipeline pipeline = pipeline("user-1");
        when(userService.findById("user-1")).thenReturn(Optional.empty());

        for (PipelineNotificationType type : PipelineNotificationType.values()) {
            PipelineNotificationEvent event = PipelineNotificationEvent.of(pipeline, type, "detail");
            listener.onPipelineNotification(event);
        }

        verify(externalMessageService, org.mockito.Mockito.times(PipelineNotificationType.values().length))
                .sendToUser(eq("user-1"), any(ExternalUserMessage.class));
    }

    @Test
    void usesOperatorIdAsNameWhenUserNotFound() {
        Pipeline pipeline = pipeline("user-1");
        when(userService.findById("user-1")).thenReturn(Optional.empty());

        PipelineNotificationEvent event = PipelineNotificationEvent.of(pipeline, PipelineNotificationType.SUCCEEDED, "ok");
        listener.onPipelineNotification(event);

        verify(externalMessageService).sendToUser(eq("user-1"), any(ExternalUserMessage.class));
    }

    @Test
    void usesOperatorIdWhenUserHasBlankUsername() {
        Pipeline pipeline = pipeline("user-1");
        User user = new User();
        user.setUsername("  ");
        when(userService.findById("user-1")).thenReturn(Optional.of(user));

        PipelineNotificationEvent event = PipelineNotificationEvent.of(pipeline, PipelineNotificationType.FAILED, "err");
        listener.onPipelineNotification(event);

        verify(externalMessageService).sendToUser(eq("user-1"), any(ExternalUserMessage.class));
    }
}
