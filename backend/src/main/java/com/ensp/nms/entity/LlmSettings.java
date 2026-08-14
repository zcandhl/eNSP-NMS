package com.ensp.nms.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "llm_settings")
public class LlmSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** openai_compatible / ollama */
    @Column(nullable = false, length = 40)
    private String provider = "ollama";

    @Column(name = "base_url", length = 500)
    private String baseUrl = "http://127.0.0.1:11434";

    /** 加密后的 API Key */
    @Column(name = "api_key_enc", columnDefinition = "TEXT")
    private String apiKeyEnc;

    @Column(length = 120)
    private String model = "qwen2.5:7b";

    @Column(nullable = false)
    private Boolean enabled = false;

    @Column
    private Double temperature = 0.3;

    @Column(name = "system_prompt_extra", columnDefinition = "TEXT")
    private String systemPromptExtra;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyEnc() { return apiKeyEnc; }
    public void setApiKeyEnc(String apiKeyEnc) { this.apiKeyEnc = apiKeyEnc; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public String getSystemPromptExtra() { return systemPromptExtra; }
    public void setSystemPromptExtra(String systemPromptExtra) { this.systemPromptExtra = systemPromptExtra; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
