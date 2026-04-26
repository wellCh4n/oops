package com.github.wellch4n.oops.service.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.config.FeishuConfig;
import com.github.wellch4n.oops.data.ExternalAccount;
import com.github.wellch4n.oops.data.ExternalAccountRepository;
import com.github.wellch4n.oops.enums.ExternalAccountProvider;
import com.github.wellch4n.oops.utils.NanoIdUtils;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.enums.CreateMessageReceiveIdTypeEnum;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "oops.feishu", name = "enabled", havingValue = "true")
public class FeishuMessageStrategy implements ExternalMessageStrategy {

    private final ExternalAccountRepository externalAccountRepository;
    private final FeishuConfig feishuConfig;
    private final Client client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FeishuMessageStrategy(ExternalAccountRepository externalAccountRepository,
                                 FeishuConfig feishuConfig) {
        this.externalAccountRepository = externalAccountRepository;
        this.feishuConfig = feishuConfig;
        this.client = Client.newBuilder(feishuConfig.getAppId(), feishuConfig.getAppSecret()).build();
    }

    @Override
    public ExternalAccountProvider getProvider() {
        return ExternalAccountProvider.FEISHU;
    }

    @Override
    public boolean isEnabled() {
        return feishuConfig.isEnabled();
    }

    @Override
    public void sendToUser(String userId, ExternalUserMessage message) {
        if (userId == null || userId.isBlank() || message == null || message.title() == null || message.title().isBlank()) {
            return;
        }

        ExternalAccount account = externalAccountRepository
                .findByProviderAndUserId(ExternalAccountProvider.FEISHU, userId)
                .orElse(null);
        if (account == null || account.getProviderUserId() == null || account.getProviderUserId().isBlank()) {
            return;
        }

        try {
            String content = objectMapper.writeValueAsString(buildCard(message));
            CreateMessageResp response = client.im().v1().message().create(
                    CreateMessageReq.newBuilder()
                            .receiveIdType(CreateMessageReceiveIdTypeEnum.USER_ID.getValue())
                            .createMessageReqBody(CreateMessageReqBody.newBuilder()
                                    .receiveId(account.getProviderUserId())
                                    .msgType("interactive")
                                    .content(content)
                                    .uuid(NanoIdUtils.generate())
                                    .build())
                            .build()
            );

            if (!response.success()) {
                throw new IllegalStateException("code=" + response.getCode() + ", msg=" + response.getMsg());
            }
        } catch (Exception e) {
            log.warn("Failed to send Feishu notification to user {}: {}", userId, e.getMessage());
        }
    }

    private Map<String, Object> buildCard(ExternalUserMessage message) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("header", Map.of(
                "template", resolveTemplate(message.level()),
                "title", Map.of("tag", "plain_text", "content", message.title())
        ));

        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(Map.of("tag", "div", "fields", buildFactFields(message.facts())));

        if (message.artifact() != null && !message.artifact().isBlank()) {
            elements.add(Map.of(
                    "tag", "div",
                    "text", markdown("**制品**\n" + message.artifact())
            ));
        }

        if (message.detail() != null && !message.detail().isBlank()) {
            elements.add(Map.of(
                    "tag", "div",
                    "text", markdown("**说明**\n" + message.detail())
            ));
        }

        card.put("elements", elements);
        return card;
    }

    private List<Map<String, Object>> buildFactFields(List<ExternalUserFact> facts) {
        List<Map<String, Object>> fields = new ArrayList<>();
        if (facts == null) {
            return fields;
        }
        for (ExternalUserFact fact : facts) {
            if (fact == null || fact.label() == null || fact.label().isBlank()) {
                continue;
            }
            fields.add(Map.of(
                    "is_short", true,
                    "text", markdown("**" + fact.label() + "**\n" + (fact.value() == null ? "-" : fact.value()))
            ));
        }
        return fields;
    }

    private Map<String, Object> markdown(String content) {
        return Map.of("tag", "lark_md", "content", content);
    }

    private String resolveTemplate(ExternalMessageLevel level) {
        if (ExternalMessageLevel.SUCCESS.equals(level)) {
            return "green";
        }
        if (ExternalMessageLevel.ERROR.equals(level)) {
            return "red";
        }
        if (ExternalMessageLevel.WARNING.equals(level)) {
            return "orange";
        }
        if (ExternalMessageLevel.NEUTRAL.equals(level)) {
            return "grey";
        }
        return "blue";
    }
}
