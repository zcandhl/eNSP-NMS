package com.ensp.nms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nms.llm")
public class LlmProperties {

    private boolean enabled = false;
    private String provider = "ollama";
    private String baseUrl = "http://127.0.0.1:11434";
    private String apiKey = "";
    private String model = "qwen2.5:7b";
    private double temperature = 0.3;
    private int timeoutSeconds = 60;
}
