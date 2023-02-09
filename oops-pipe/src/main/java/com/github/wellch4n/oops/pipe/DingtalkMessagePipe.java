package com.github.wellch4n.oops.pipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.common.core.Description;
import com.github.wellch4n.oops.common.core.DescriptionPipeParam;
import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipelineContext;
import io.kubernetes.client.openapi.models.V1Container;
import lombok.Data;

import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/2/2
 */

@Description(title = "钉钉消息")
public class DingtalkMessagePipe extends Pipe<DingtalkMessagePipe.Input> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    public DingtalkMessagePipe(Map<String, Object> initParams) {
        super(initParams);
    }

    @Override
    public void build(V1Container container, PipelineContext context, StringBuilder commandBuilder) {

        String content = "";
        try {
            MessageBody messageBody = new MessageBody();
            messageBody.setMessageContent("finished");
            content = objectMapper.writeValueAsString(messageBody);
        } catch (Exception ignored) {}

        String commandTemplate = """
                curl --location --request POST '%s' \\
                --header 'Content-Type: application/json' \\
                --data-raw '%s ';
                """;

        String webhookUrl = (String) getParam(Input.webhook);
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

    public enum Input implements DescriptionPipeParam {
        webhook {
            @Override
            public String description() {
                return "钉钉webhook";
            }

            @Override
            public Class<?> clazz() {
                return String.class;
            }
        }
    }
}
