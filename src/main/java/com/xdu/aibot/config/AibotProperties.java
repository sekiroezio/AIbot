package com.xdu.aibot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "aibot")
public class AibotProperties {

    private String chatModelType = "cloud";
    private Map<String, String> relationTypeMapping = new HashMap<>();

    public String mapRelationType(String input) {
        if (input == null || input.isBlank()) return "RELATED_TO";

        String trimmed = input.trim();

        if (trimmed.matches("[A-Z][A-Z0-9_]*")) {
            return trimmed;
        }

        for (Map.Entry<String, String> entry : relationTypeMapping.entrySet()) {
            String standardType = entry.getKey();
            String keywords = entry.getValue();
            for (String keyword : keywords.split(",")) {
                String kw = keyword.trim();
                if (kw.isEmpty()) continue;
                if (trimmed.equals(kw) || trimmed.contains(kw) || kw.contains(trimmed)) {
                    return standardType;
                }
            }
        }

        String sanitized = trimmed.toUpperCase()
                .replaceAll("[^A-Z0-9_\\u4e00-\\u9fa5]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return sanitized.isEmpty() ? "RELATED_TO" : sanitized;
    }
}
