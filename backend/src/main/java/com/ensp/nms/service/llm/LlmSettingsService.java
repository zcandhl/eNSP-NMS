package com.ensp.nms.service.llm;

import com.ensp.nms.config.LlmProperties;
import com.ensp.nms.entity.LlmSettings;
import com.ensp.nms.repository.LlmSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LlmSettingsService {

    private final LlmSettingsRepository repository;
    private final LlmProperties defaults;
    private final SecretCrypto crypto;

    @Transactional
    public LlmSettings getOrCreate() {
        return repository.findAll().stream().findFirst().orElseGet(() -> {
            LlmSettings s = new LlmSettings();
            s.setEnabled(defaults.isEnabled());
            s.setProvider(defaults.getProvider());
            s.setBaseUrl(defaults.getBaseUrl());
            s.setModel(defaults.getModel());
            s.setTemperature(defaults.getTemperature());
            if (defaults.getApiKey() != null && !defaults.getApiKey().isBlank()) {
                s.setApiKeyEnc(crypto.encrypt(defaults.getApiKey()));
            }
            return repository.save(s);
        });
    }

    @Transactional
    public Map<String, Object> toPublicView() {
        LlmSettings s = getOrCreate();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", Boolean.TRUE.equals(s.getEnabled()));
        m.put("provider", s.getProvider());
        m.put("baseUrl", s.getBaseUrl());
        m.put("model", s.getModel());
        m.put("temperature", s.getTemperature());
        m.put("systemPromptExtra", s.getSystemPromptExtra() != null ? s.getSystemPromptExtra() : "");
        m.put("hasApiKey", s.getApiKeyEnc() != null && !s.getApiKeyEnc().isBlank());
        m.put("apiKey", maskKey(s));
        m.put("timeoutSeconds", defaults.getTimeoutSeconds());
        m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
        return m;
    }

    /** 运行时解析后的明文配置（含解密 Key） */
    @Transactional
    public ResolvedSettings resolve() {
        LlmSettings s = getOrCreate();
        String key = null;
        try {
            key = crypto.decrypt(s.getApiKeyEnc());
        } catch (Exception ignored) {
            key = null;
        }
        return new ResolvedSettings(
                Boolean.TRUE.equals(s.getEnabled()),
                s.getProvider() != null ? s.getProvider() : "ollama",
                s.getBaseUrl(),
                key,
                s.getModel(),
                s.getTemperature() != null ? s.getTemperature() : defaults.getTemperature(),
                s.getSystemPromptExtra(),
                defaults.getTimeoutSeconds()
        );
    }

    @Transactional
    public Map<String, Object> update(Map<String, Object> body) {
        LlmSettings s = getOrCreate();
        if (body.containsKey("enabled")) {
            s.setEnabled(Boolean.TRUE.equals(body.get("enabled"))
                    || "true".equalsIgnoreCase(String.valueOf(body.get("enabled"))));
        }
        if (body.get("provider") != null) {
            s.setProvider(String.valueOf(body.get("provider")).trim());
        }
        if (body.get("baseUrl") != null) {
            s.setBaseUrl(String.valueOf(body.get("baseUrl")).trim());
        }
        if (body.get("model") != null) {
            s.setModel(String.valueOf(body.get("model")).trim());
        }
        if (body.get("temperature") != null) {
            try {
                s.setTemperature(Double.parseDouble(String.valueOf(body.get("temperature"))));
            } catch (NumberFormatException ignored) {
            }
        }
        if (body.containsKey("systemPromptExtra")) {
            Object extra = body.get("systemPromptExtra");
            s.setSystemPromptExtra(extra == null ? null : String.valueOf(extra));
        }
        if (body.containsKey("apiKey")) {
            String apiKey = body.get("apiKey") == null ? "" : String.valueOf(body.get("apiKey")).trim();
            if (!apiKey.isEmpty() && !crypto.isMasked(apiKey)) {
                s.setApiKeyEnc(crypto.encrypt(apiKey));
            }
            // **** 或空：保留旧值
        }
        repository.save(s);
        return toPublicView();
    }

    private String maskKey(LlmSettings s) {
        if (s.getApiKeyEnc() == null || s.getApiKeyEnc().isBlank()) {
            return "";
        }
        try {
            String plain = crypto.decrypt(s.getApiKeyEnc());
            if (plain == null || plain.isBlank()) {
                return "";
            }
            if (plain.length() <= 4) {
                return "****";
            }
            return plain.substring(0, 2) + "****" + plain.substring(plain.length() - 2);
        } catch (Exception e) {
            return "****";
        }
    }

    public record ResolvedSettings(
            boolean enabled,
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            double temperature,
            String systemPromptExtra,
            int timeoutSeconds
    ) {}
}
