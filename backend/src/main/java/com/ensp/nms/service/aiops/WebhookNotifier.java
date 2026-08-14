package com.ensp.nms.service.aiops;

import com.ensp.nms.config.AiopsPolicyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AIOps 收敛摘要 Webhook 推送（异步）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookNotifier {

    private final AiopsPolicyProperties policyProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Async
    public void sendDigestAsync(Map<String, Object> payload) {
        try {
            sendDigest(payload);
        } catch (Exception e) {
            log.warn("Webhook 异步推送异常: {}", e.getMessage());
        }
    }

    public Map<String, Object> testWebhook() {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("type", "aiops_test");
        sample.put("message", "eNSP NMS AIOps webhook 连通测试");
        sample.put("at", java.time.LocalDateTime.now().toString());
        return sendDigestInternal(sample, true);
    }

    public Map<String, Object> sendDigest(Map<String, Object> payload) {
        return sendDigestInternal(payload, false);
    }

    private Map<String, Object> sendDigestInternal(Map<String, Object> payload, boolean forceForTest) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!forceForTest && !policyProperties.isWebhookEnabled()) {
            result.put("ok", false);
            result.put("webhook", "disabled");
            return result;
        }
        String url = policyProperties.getWebhookUrl();
        if (url == null || url.isBlank()) {
            result.put("ok", false);
            if (!forceForTest) {
                result.put("error", "Webhook URL 为空");
            }
            return result;
        }
        try {
            String body = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            result.put("ok", ok);
            result.put("statusCode", resp.statusCode());
            result.put("body", truncate(resp.body(), 200));
            if (!ok) {
                log.warn("Webhook 响应异常 HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 120));
            } else {
                log.info("Webhook 推送成功 → {}", url);
            }
            return result;
        } catch (Exception e) {
            log.warn("Webhook 推送失败: {}", e.getMessage());
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
