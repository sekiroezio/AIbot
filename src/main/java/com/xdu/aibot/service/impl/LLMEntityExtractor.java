package com.xdu.aibot.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xdu.aibot.pojo.entity.Entity;
import com.xdu.aibot.pojo.entity.ExtractionResult;
import com.xdu.aibot.pojo.entity.Relationship;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class LLMEntityExtractor {

    private final ChatClient extractionChatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LLMEntityExtractor(ChatClient extractionChatClient) {
        this.extractionChatClient = extractionChatClient;
    }

    private static final String ENTITY_EXTRACTION_PROMPT = """
            你是一个知识图谱构建专家。请从以下文本中抽取知识实体。
            
            要求：
            1. 识别文本中的重要实体（人物、组织、地点、概念、事件等）
            2. 每个实体包含：name(名称), type(类型), description(描述)
            3. 实体类型使用英文：Person, Organization, Location, Concept, Event, Technology, Product, Time, Other
            4. 最多抽取10个最重要的实体，按重要性排序
            5. 严格按照JSON格式输出，不要输出其他内容
            
            输出格式：
            {
              "entities": [
                {"name": "实体名称", "type": "Person", "description": "简短描述"}
              ]
            }
            
            文本内容：
            %s
            
            请输出JSON：
            """;

    private static final String RELATION_EXTRACTION_PROMPT = """
            根据以下文本和已识别的实体列表，抽取实体之间的关系。
            
            已识别的实体列表：
            %s
            
            要求：
            1. 识别实体之间的关系
            2. 每个关系包含：source(源实体名称), target(目标实体名称), name(关系名称), type(关系类型), description(关系描述)
            3. 关系类型使用大写英文加下划线格式，如：CREATED_BY, BELONGS_TO, LOCATED_IN, STUDIES_AT, TEACHES, MANAGES, WORKS_FOR, PARTICIPATES_IN, HELPS, OPPOSES, FRIEND_OF, FAMILY_OF, SPOUSE_OF, TEACHER_OF, HAPPENED_AT, PART_OF, INFLUENCED_BY, RELATED_TO
            4. 如果以上类型都不合适，可以自定义关系类型（使用大写英文+下划线格式）
            5. 关系名称(name)使用简洁的中文，如"创作"、"学习于"、"管理"
            6. 关系描述(description)用1-2句话说明关系
            7. 严格按照JSON格式输出，不要输出其他内容
            
            输出格式：
            {
              "relations": [
                {"source": "源实体名称", "target": "目标实体名称", "name": "关系名称", "type": "CREATED_BY", "description": "关系描述"}
              ]
            }
            
            文本内容：
            %s
            
            请输出JSON：
            """;

    public ExtractionResult extract(String text) {
        try {
            log.info("开始 LLM 实体抽取，文本长度: {}", text.length());

            List<Entity> entities = extractEntities(text);
            List<Relationship> relationships = extractRelations(text, entities);

//            ExtractionResult result = deduplicate(new ExtractionResult(entities, relationships));
            ExtractionResult result = new ExtractionResult(entities, relationships);

            log.info("LLM 抽取完成: {} 个实体, {} 个关系",
                    result.getEntities().size(),
                    result.getRelationships().size());

            return result;
        } catch (Exception e) {
            log.error("LLM 实体抽取失败", e);
            return new ExtractionResult();
        }
    }

    public ExtractionResult extractBatch(List<String> chunks) {
        List<Entity> allEntities = new ArrayList<>();
        List<Relationship> allRelationships = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            try {
                log.info("处理第 {} / {} 个文本块", i + 1, chunks.size());
                ExtractionResult result = extract(chunk);
                allEntities.addAll(result.getEntities());
                allRelationships.addAll(result.getRelationships());

//                if (i < chunks.size() - 1) {
//                    Thread.sleep(200);
//                }
            } catch (Exception e) {
                log.error("处理文本块 {} 失败", i, e);
            }
        }

        ExtractionResult mergedResult = new ExtractionResult(allEntities, allRelationships);
//        return deduplicate(mergedResult);
        return mergedResult;
    }

    private List<Entity> extractEntities(String text) {
        String prompt = String.format(ENTITY_EXTRACTION_PROMPT, text);
        String response = callLlm(prompt);
        return parseEntityResponse(response);
    }

    private List<Relationship> extractRelations(String text, List<Entity> entities) {
        if (entities.isEmpty()) return List.of();

        String entityListStr = buildEntityListString(entities);
        String prompt = String.format(RELATION_EXTRACTION_PROMPT, entityListStr, text);
        String response = callLlm(prompt);
        return parseRelationResponse(response);
    }

    private String callLlm(String prompt) {
        return extractionChatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    private String buildEntityListString(List<Entity> entities) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            sb.append(i + 1).append(". ").append(e.getName()).append("(").append(e.getType()).append(")");
            if (e.getDescription() != null && !e.getDescription().isEmpty()) {
                sb.append(" - ").append(e.getDescription());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private List<Entity> parseEntityResponse(String response) {
        List<Entity> entities = new ArrayList<>();
        if (response == null || response.isBlank()) return entities;

        try {
            String jsonStr = extractJson(response);
            if (jsonStr == null) {
                log.warn("未找到有效JSON: {}", response.substring(0, Math.min(200, response.length())));
                return entities;
            }

            Map<String, Object> map = objectMapper.readValue(jsonStr, Map.class);

            Object entitiesObj = map.get("entities");
            if (entitiesObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> entityMap) {
                        Entity entity = Entity.builder()
                                .name(getStringValue(entityMap, "name"))
                                .type(mapEntityType(getStringValue(entityMap, "type")))
                                .description(getStringValue(entityMap, "description"))
                                .build();
                        if (entity.getName() != null && !entity.getName().isBlank()) {
                            entities.add(entity);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析实体响应失败: {}", e.getMessage());
        }

        return entities;
    }

    private List<Relationship> parseRelationResponse(String response) {
        List<Relationship> relationships = new ArrayList<>();
        if (response == null || response.isBlank()) return relationships;

        try {
            String jsonStr = extractJson(response);
            if (jsonStr == null) {
                log.warn("未找到有效JSON: {}", response.substring(0, Math.min(200, response.length())));
                return relationships;
            }

            Map<String, Object> map = objectMapper.readValue(jsonStr, Map.class);

            Object relationsObj = map.get("relations");
            if (relationsObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> relMap) {
                        Relationship rel = Relationship.builder()
                                .source(getStringValue(relMap, "source"))
                                .target(getStringValue(relMap, "target"))
                                .name(getStringValue(relMap, "name"))
                                .type(getStringValue(relMap, "type"))
                                .description(getStringValue(relMap, "description"))
                                .strength(5)
                                .build();
                        if (rel.getSource() != null && rel.getTarget() != null
                                && !rel.getSource().isBlank() && !rel.getTarget().isBlank()) {
                            relationships.add(rel);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析关系响应失败: {}", e.getMessage());
        }

        return relationships;
    }

    private String getStringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString().trim() : null;
    }

    private String extractJson(String text) {
        if (text == null || text.isEmpty()) return null;

        text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "");

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');

        if (start >= 0 && end > start) {
            String json = text.substring(start, end + 1);
            return sanitizeJson(json);
        }

        return null;
    }

    private String sanitizeJson(String json) {
        if (json == null) return null;

        String sanitized = json;
        String pattern = "\"\\s*\\+\\s*\"";
        while (sanitized.contains("+") && sanitized.matches("(?s).*" + pattern + ".*")) {
            sanitized = sanitized.replaceAll(pattern, "");
        }

        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < sanitized.length(); i++) {
            char c = sanitized.charAt(i);
            if (escaped) {
                result.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                result.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                result.append(c);
                continue;
            }
            if (inString && (c == '\n' || c == '\r')) {
                result.append(' ');
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    private String mapEntityType(String type) {
        if (type == null || type.isBlank()) return "Other";

        return switch (type.toLowerCase().trim()) {
            case "person", "人物", "人名" -> "Person";
            case "organization", "组织", "机构", "公司" -> "Organization";
            case "location", "地点", "地区", "城市" -> "Location";
            case "concept", "概念" -> "Concept";
            case "event", "事件" -> "Event";
            case "technology", "技术" -> "Technology";
            case "product", "产品" -> "Product";
            case "time", "时间" -> "Time";
            default -> "Other";
        };
    }
}
