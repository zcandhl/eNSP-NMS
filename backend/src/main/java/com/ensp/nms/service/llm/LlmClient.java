package com.ensp.nms.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public String chat(String provider, String baseUrl, String apiKey, String model,
                       double temperature, int timeoutSeconds,
                       List<Map<String, String>> messages) throws Exception {
        String p = provider == null ? "ollama" : provider.trim().toLowerCase();
        if ("openai_compatible".equals(p) || "openai".equals(p)) {
            return chatOpenAiCompatible(baseUrl, apiKey, model, temperature, timeoutSeconds, messages);
        }
        return chatOllama(baseUrl, model, temperature, timeoutSeconds, messages);
    }

    private String chatOpenAiCompatible(String baseUrl, String apiKey, String model,
                                        double temperature, int timeoutSeconds,
                                        List<Map<String, String>> messages) throws Exception {
        String root = trimSlash(baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com" : baseUrl);
        String url = root.endsWith("/v1") ? root + "/chat/completions" : root + "/v1/chat/completions";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("messages", messages);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("LLM HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
        }
        JsonNode rootNode = objectMapper.readTree(resp.body());
        JsonNode content = rootNode.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("LLM 响应无内容");
        }
        return content.asText().trim();
    }

    private String chatOllama(String baseUrl, String model, double temperature, int timeoutSeconds,
                              List<Map<String, String>> messages) throws Exception {
        String root = trimSlash(baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:11434" : baseUrl);
        String url = root + "/api/chat";

        List<Map<String, String>> ollamaMsgs = new ArrayList<>();
        for (Map<String, String> m : messages) {
            Map<String, String> one = new LinkedHashMap<>();
            one.put("role", m.getOrDefault("role", "user"));
            one.put("content", m.getOrDefault("content", ""));
            ollamaMsgs.add(one);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", ollamaMsgs);
        body.put("options", Map.of("temperature", temperature));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("Ollama HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
        }
        JsonNode rootNode = objectMapper.readTree(resp.body());
        String content = rootNode.path("message").path("content").asText();
        if (content == null || content.isBlank()) {
            // 兼容部分旧格式
            content = rootNode.path("response").asText();
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Ollama 响应无内容，请确认模型已拉取：" + model);
        }
        return content.trim();
    }

    private static String trimSlash(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
