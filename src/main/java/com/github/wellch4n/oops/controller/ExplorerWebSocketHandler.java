//package com.github.wellch4n.oops.controller;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
////import com.github.wellch4n.oops.config.EnvironmentContext;
////import com.github.wellch4n.oops.config.KubernetesClientFactory;
//import com.github.wellch4n.oops.objects.FileEntryResponse;
//import io.kubernetes.client.Exec;
//import org.jetbrains.annotations.NotNull;
//import org.springframework.web.socket.*;
//import org.springframework.web.socket.handler.TextWebSocketHandler;
//import org.springframework.web.util.UriComponentsBuilder;
//
//import java.io.IOException;
//import java.io.InputStream;
//import java.io.OutputStream;
//import java.net.URI;
//import java.nio.charset.StandardCharsets;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
///**
// * @author wellCh4n
// * @date 2025/7/15
// */
//public class ExplorerWebSocketHandler extends TextWebSocketHandler {
//
//    private Process process;
//    private OutputStream stdin;
//    private Thread thread;
//
//    private Thread pingThread;
//
//    private final ObjectMapper objectMapper = new ObjectMapper();
//    private static final String LS_COMMAND_TEMPLATE = "ls -lh %s \n";
//
//    private static final Pattern LS_LINE_PATTERN = Pattern.compile(
//            "^([\\-a-z]+)\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+([A-Z][a-z]{2,3}\\s+\\d{1,2}\\s+(?:\\d{2}:\\d{2}|\\d{4}))\\s+(.+)$"
//    );
//
//    @Override
//    public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
//
//        URI uri = session.getUri();
//        String path = uri.getPath();
//
//        String[] parts = path.split("/");
//        String namespace = parts[3];
//        String app = parts[5];
//        String pod = parts[7];
//
//        Map<String, String> params = UriComponentsBuilder
//                .fromUriString(uri.toString())
//                .build()
//                .getQueryParams()
//                .toSingleValueMap();
//        String environment = params.get("environment");
//        EnvironmentContext.setEnvironment(environment);
//
//        Exec exec = new Exec(KubernetesClientFactory.getClient());
//        this.process = exec.exec(
//                namespace,
//                pod,
//                new String[]{"/bin/sh"},
//                app,
//                true, true
//        );
//
//        this.stdin = process.getOutputStream();
//
//        this.thread = Thread.startVirtualThread(() -> {
//            try (InputStream out = process.getInputStream()) {
//                byte[] buffer = new byte[1024];
//                int len;
//                StringBuilder outputBuffer = new StringBuilder();
//
//                while ((len = out.read(buffer)) != -1 && session.isOpen()) {
//                    String chunk = new String(buffer, 0, len, StandardCharsets.UTF_8);
//                    outputBuffer.append(chunk);
//
//                    if (outputBuffer.toString().matches("(?s).*\\n?[#$] ?$")) {
//                        FileEntryResponse fileEntryResponse = parseFileEntries(outputBuffer.toString());
//                        String content = objectMapper.writeValueAsString(fileEntryResponse);
//                        session.sendMessage(new TextMessage(content));
//                        outputBuffer.setLength(0);
//                    }
//                }
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        });
//
//        this.pingThread = Thread.startVirtualThread(() -> {
//            while (session.isOpen()) {
//                try {
//                    session.sendMessage(new PingMessage());
//                    Thread.sleep(10_000);
//                } catch (Exception e) {
//                    break;
//                }
//            }
//        });
//
//    }
//
//    @Override
//    protected void handleTextMessage(@NotNull WebSocketSession session, @NotNull TextMessage message) throws Exception {
//        if (stdin != null) {
//            String text = message.getPayload();
//            String command = LS_COMMAND_TEMPLATE.formatted(text);
//            try {
//                stdin.write(command.getBytes(StandardCharsets.UTF_8));
//                stdin.flush();
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
//    }
//
//    @Override
//    public void afterConnectionClosed(@NotNull WebSocketSession session, @NotNull CloseStatus status) throws Exception {
//        if (process != null) {
//            process.destroy();
//        }
//        if (stdin != null) {
//            try {
//                stdin.close();
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
//        if (thread != null && thread.isAlive()) {
//            thread.interrupt();
//        }
//        if (pingThread != null && pingThread.isAlive()) {
//            pingThread.interrupt();
//        }
//        session.close();
//    }
//
//    private FileEntryResponse parseFileEntries(String output) {
//        if (output.startsWith("# ")) {
//            output = output.replaceAll("# ", "");
//        }
//
//        String[] lines = output.split("\\r?\\n");
//
//        String path = "";
//
//        FileEntryResponse entry = new FileEntryResponse();
//        List<FileEntryResponse.FileEntry> files = new ArrayList<>();
//        for (String line : lines) {
//            if (line.startsWith("ls -lh")) {
//                path = line.replaceAll("ls -lh ", "").trim();
//                entry.setPwd(path);
//                continue;
//            }
//
//            if (line.trim().isEmpty() || line.startsWith("total") || line.startsWith("#")) {
//                continue;
//            }
//
//            Matcher matcher = LS_LINE_PATTERN.matcher(line);
//            if (matcher.matches()) {
//                FileEntryResponse.FileEntry fileEntry = new FileEntryResponse.FileEntry();
//                fileEntry.setPermissions(matcher.group(1));
//                fileEntry.setLinks(Integer.parseInt(matcher.group(2)));
//                fileEntry.setOwner(matcher.group(3));
//                fileEntry.setGroup(matcher.group(4));
//                fileEntry.setSize(matcher.group(5));
//                fileEntry.setDate(matcher.group(6));
//                fileEntry.setName(matcher.group(7));
//
//                fileEntry.setAbsolutePath(path, fileEntry.getName());
//                files.add(fileEntry);
//            }
//        }
//
//        entry.setItems(files);
//
//        return entry;
//    }
//}
