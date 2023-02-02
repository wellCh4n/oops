package com.github.wellch4n.oops.pipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipelineContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.kubernetes.client.openapi.models.V1Container;
import lombok.Data;

import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/2/2
 */
public class DingtalkMessagePipe extends Pipe {

    private final String webhookUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    public DingtalkMessagePipe(Map<String, Object> initParams) {
        super(initParams);
        this.webhookUrl = (String) initParams.get("webhook");
    }

    @Override
    public void build(V1Container container, PipelineContext context, StringBuilder commandBuilder) {
        String projectName = (String) context.get("${git.repoPath}");

        String content = "";
        try {
            MessageBody messageBody = new MessageBody();
            messageBody.setMessageContent(projectName + " finished");
            content = objectMapper.writeValueAsString(messageBody);
        } catch (Exception ignored) {}

        String commandTemplate = """
                curl --location --request POST '%s' \\
                --header 'Content-Type: application/json' \\
                --data-raw '%s ';
                """;
        String cmd = String.format(commandTemplate, webhookUrl, content);
        commandBuilder.append(cmd);
    }

    @Data
    public static class MessageBody {
        private String msgtype = "text";
        private MessageText text;

        @Data
        public static class MessageText {
            private String content;
        }

        public void setMessageContent(String content) {
            if (text == null) {
                text = new MessageText();
            }
            text.setContent(content);
        }
    }
}
