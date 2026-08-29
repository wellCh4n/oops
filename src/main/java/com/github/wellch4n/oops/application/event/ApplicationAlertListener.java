package com.github.wellch4n.oops.application.event;

import com.github.wellch4n.oops.application.dto.ResourceMetric;
import com.github.wellch4n.oops.application.port.external.ExternalMessageLevel;
import com.github.wellch4n.oops.application.port.external.ExternalUserFact;
import com.github.wellch4n.oops.application.port.external.ExternalUserMessage;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.service.ExternalMessageService;
import com.github.wellch4n.oops.domain.application.Application;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Turns an alert transition into a message for the application's owner.
 *
 * <p>Runs on the notification executor rather than the scan thread, which is also why it can afford to look the
 * application up in the database: transitions are rare, so this is not on the once-a-minute path.
 */
@Slf4j
@Component
public class ApplicationAlertListener {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] BYTE_UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};

    /** Beyond this the pod list stops being information and starts being a wall of text. */
    private static final int MAX_LISTED_PODS = 10;

    private final ExternalMessageService externalMessageService;
    private final ApplicationRepository applicationRepository;

    public ApplicationAlertListener(ExternalMessageService externalMessageService,
                                    ApplicationRepository applicationRepository) {
        this.externalMessageService = externalMessageService;
        this.applicationRepository = applicationRepository;
    }

    @Async("notificationTaskExecutor")
    @EventListener
    public void onApplicationAlert(ApplicationAlertEvent event) {
        Application application = applicationRepository.findAggregate(event.namespace(), event.applicationName());
        if (application == null || StringUtils.isBlank(application.getOwner())) {
            // Nothing to do but say so: an application with no owner has no one to tell, and silently dropping the
            // alert would look identical to the alert never having fired.
            log.warn("Resource alert for {}/{} has no owner to notify",
                    event.namespace(), event.applicationName());
            return;
        }

        try {
            externalMessageService.sendToUser(application.getOwner(), buildMessage(event));
        } catch (Exception exception) {
            log.warn("Failed to send resource alert for {}/{}: {}",
                    event.namespace(), event.applicationName(), exception.getMessage(), exception);
        }
    }

    private ExternalUserMessage buildMessage(ApplicationAlertEvent event) {
        List<ExternalUserFact> facts = new ArrayList<>();
        facts.add(new ExternalUserFact("应用", event.namespace() + "/" + event.applicationName()));
        facts.add(new ExternalUserFact("环境", event.environment()));
        facts.add(new ExternalUserFact("指标", metricName(event.metric())));
        facts.add(new ExternalUserFact("阈值", event.thresholdPercent() + "%（"
                + formatValue(event.metric(), event.threshold()) + " / "
                + formatValue(event.metric(), event.limitValue()) + "）"));
        facts.add(new ExternalUserFact("触发 Pod", formatPods(event.pods())));
        if (event.firingSince() != null) {
            facts.add(new ExternalUserFact("开始时间", event.firingSince().format(TIME_FORMATTER)));
        }
        facts.add(new ExternalUserFact("时间", event.occurredAt().format(TIME_FORMATTER)));

        return new ExternalUserMessage(
                "Oops 资源告警｜" + resolveTitle(event),
                resolveLevel(event.type()),
                facts,
                resolveDetail(event),
                null);
    }

    private String resolveTitle(ApplicationAlertEvent event) {
        String metric = metricName(event.metric());
        if (event.type() == ApplicationAlertType.RESOLVED) {
            return metric + "已恢复";
        }
        return event.repeated() ? metric + "持续超限" : metric + "超过阈值";
    }

    private String resolveDetail(ApplicationAlertEvent event) {
        String metric = metricName(event.metric());
        if (event.type() == ApplicationAlertType.RESOLVED) {
            String duration = formatDuration(event.firingSince(), event.occurredAt());
            return duration == null
                    ? metric + "已回落到阈值以下。"
                    : metric + "已回落到阈值以下，本次持续 " + duration + "。";
        }
        String base = metric + "使用量持续高于 limit 的 " + event.thresholdPercent() + "%。";
        if (event.metric() == ResourceMetric.MEMORY) {
            return base + "继续上涨会触发 OOMKill，建议确认是否存在内存泄漏，或调高内存 limit。";
        }
        return base + "容器正在被 CPU 限流，延迟会因此上升，建议确认负载是否异常，或调高 CPU limit。";
    }

    private ExternalMessageLevel resolveLevel(ApplicationAlertType type) {
        return type == ApplicationAlertType.RESOLVED ? ExternalMessageLevel.SUCCESS : ExternalMessageLevel.WARNING;
    }

    private String metricName(ResourceMetric metric) {
        return metric == ResourceMetric.CPU ? "CPU" : "内存";
    }

    private String formatPods(List<String> pods) {
        if (pods == null || pods.isEmpty()) {
            return "-";
        }
        if (pods.size() <= MAX_LISTED_PODS) {
            return String.join(", ", pods);
        }
        return String.join(", ", pods.subList(0, MAX_LISTED_PODS))
                + " 等 " + pods.size() + " 个 Pod";
    }

    private String formatValue(ResourceMetric metric, long value) {
        if (metric == ResourceMetric.CPU) {
            return value + "m";
        }
        double amount = value;
        int unit = 0;
        while (amount >= 1024 && unit < BYTE_UNITS.length - 1) {
            amount /= 1024;
            unit++;
        }
        return unit == 0
                ? (long) amount + BYTE_UNITS[unit]
                : String.format("%.1f%s", amount, BYTE_UNITS[unit]);
    }

    private String formatDuration(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return null;
        }
        long minutes = Duration.between(from, to).toMinutes();
        if (minutes < 1) {
            return "不足 1 分钟";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        return minutes / 60 + " 小时 " + minutes % 60 + " 分钟";
    }
}
