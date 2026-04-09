package com.xdu.aibot.service;

import com.xdu.aibot.pojo.entity.Entity;
import com.xdu.aibot.pojo.entity.Relationship;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 LLM 的动态实体关系抽取服务
 * 参考微软 GraphRAG 设计，动态抽取实体，不硬编码 schema
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMEntityExtractor {

    private final ChatClient extractionChatClient;

    /**
     * LLM 实体关系抽取 Prompt（中文优化版）
     * 约束：
     * 1. 每次最多抽取 10 个实体（优先人物）
     * 2. 关系名统一标准化，不包含具体实体名
     */
    private static final String EXTRACTION_PROMPT = """
            # 任务
            从给定文本中抽取最重要的实体及其关系。
                
            ## 重要约束
            1. **实体数量限制**：每次最多抽取 10 个实体
            2. **实体优先级**：人物 > 地点 > 组织 > 其他
            3. **关系名标准化**：
               - 不在关系名中出现具体实体名
               - 使用统一的关系类型，如："属于"、"创造"、"发生在"、"参与"、"与...相关"
               - 关系名简洁，通常 2-4 个字
                
            ## 步骤 1：实体识别
            只识别最重要的实体（最多 10 个），按重要性排序：
            - entity_name: 实体名称
            - entity_type: 实体类型（人物/地点/组织/其他）
            - entity_description: 一句话描述
                
            ## 步骤 2：关系识别
            识别关系时：
            - source_entity: 源实体名称
            - target_entity: 目标实体名称
            - relationship_type: 关系类型（标准化，如：是、属于、创造、发生在、参与、与...相关）
            - relationship_description: 用 1-2 句话说明关系
                
            ## 示例
                
            文本：
            哈利·波特系列是英国作家J.K.罗琳创作的魔法文学。哈利在霍格沃茨魔法学校学习，由邓布利多校长教导。
                
            正确输出：
            ("entity"<|>哈利·波特<|>人物<|>魔法学校学生，系列主角)
            ##
            ("entity"<|>J.K.罗琳<|>人物<|>英国作家，系列作者)
            ##
            ("entity"<|>霍格沃茨魔法学校<|>地点<|>魔法学校，哈利学习的地方)
            ##
            ("entity"<|>邓布利多<|>人物<|>霍格沃茨校长)
            ##
            ("relationship"<|>哈利·波特<|>J.K.罗琳<|>创造<|>J.K.罗琳创作了哈利·波特系列)
            ##
            ("relationship"<|>哈利·波特<|>霍格沃茨魔法学校<|>学习<|>哈利在霍格沃茨学习魔法)
            ##
            ("relationship"<|>邓布利多<|>霍格沃茨魔法学校<|>管理<|>邓布利多是霍格沃茨校长)
                
            错误输出（关系名包含实体名）：
            ("relationship"<|>J.K.罗琳<|>哈利·波特<|>J.K.罗琳创作<|>错误的关系名)
                
            ## 待处理文本
            {text}
            """;

    /**
     * 合并多个抽取结果时使用的去重 Prompt
     */
    private static final String MERGE_PROMPT = """
            # 任务
            合并多个实体列表，消除重复实体。
            
            ## 去重规则
            1. 如果两个实体名称指代同一事物，保留最完整准确的名称
            2. 如果两个实体描述同一事物，合并描述
            3. 保留所有唯一的关系
            
            实体列表：
            {entities}
            
            关系列表：
            {relationships}
            
            直接输出合并后的结果，格式同上。
            """;

    /**
     * 从文本中抽取实体关系
     */
    public ExtractionResult extract(String text) {
        try {
            log.info("开始 LLM 实体抽取，文本长度: {}", text.length());
            
            String prompt = EXTRACTION_PROMPT.replace("{text}", text);
            
            String response = extractionChatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            
            log.debug("LLM 返回: {}", response);
            
            ExtractionResult result = parseResponse(response);
            
            // 规则去重
            result = deduplicate(result);
            
            log.info("LLM 抽取完成: {} 个实体, {} 个关系", 
                    result.getEntities().size(), 
                    result.getRelationships().size());
            
            return result;
        } catch (Exception e) {
            log.error("LLM 实体抽取失败", e);
            return new ExtractionResult();
        }
    }

    /**
     * 批量抽取（分块处理）
     */
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
                
                // 避免频率限制
                if (i < chunks.size() - 1) {
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                log.error("处理文本块 {} 失败", i, e);
            }
        }
        
        // 合并去重
        ExtractionResult mergedResult = new ExtractionResult(allEntities, allRelationships);
        return deduplicate(mergedResult);
    }

    /**
     * 解析 LLM 响应
     */
    private ExtractionResult parseResponse(String response) {
        List<Entity> entities = new ArrayList<>();
        List<Relationship> relationships = new ArrayList<>();
        
        // 分割响应
        String[] parts = response.split("##");
        
        Pattern entityPattern = Pattern.compile("\\(\"entity\"<\\|>(.+?)<\\|>(.+?)<\\|>(.+?)\\)");
        Pattern relPattern = Pattern.compile("\\(\"relationship\"<\\|>(.+?)<\\|>(.+?)<\\|>(.+?)<\\|>(.+?)\\)");
        
        for (String part : parts) {
            part = part.trim();
            
            // 移除完成标记
            part = part.replace("<|COMPLETE|>", "").trim();
            if (part.isEmpty()) continue;
            
            // 解析实体
            Matcher entityMatcher = entityPattern.matcher(part);
            if (entityMatcher.find()) {
                Entity entity = Entity.builder()
                        .name(entityMatcher.group(1).trim())
                        .type(entityMatcher.group(2).trim())
                        .description(entityMatcher.group(3).trim())
                        .build();
                entities.add(entity);
            }
            
            // 解析关系
            Matcher relMatcher = relPattern.matcher(part);
            if (relMatcher.find()) {
                String source = relMatcher.group(1).trim();
                String target = relMatcher.group(2).trim();
                String type = relMatcher.group(3).trim();
                String desc = relMatcher.group(4).trim();
                
                // 跳过关系描述中包含实体名的情况
                if (desc.contains(source) || desc.contains(target)) {
                    // 关系描述应该重新生成
                    desc = source + "与" + target + "的关系";
                }
                
                Relationship rel = Relationship.builder()
                        .source(source)
                        .target(target)
                        .description(desc)
                        .type(type) // 使用标准化关系类型
                        .strength(5)
                        .build();
                relationships.add(rel);
            }
        }
        
        return new ExtractionResult(entities, relationships);
    }

    private int parseStrength(String str) {
        try {
            return Math.max(1, Math.min(10, Integer.parseInt(str.trim())));
        } catch (Exception e) {
            return 5; // 默认中等强度
        }
    }

    /**
     * 规则去重
     */
    private ExtractionResult deduplicate(ExtractionResult result) {
        List<Entity> entities = result.getEntities();
        List<Relationship> relationships = result.getRelationships();
        
        // 1. 字符串精确去重
        Set<String> seenNames = new HashSet<>();
        entities = entities.stream()
                .filter(e -> seenNames.add(e.getName().toLowerCase().trim()))
                .toList();
        
        // 2. 相似度去重
        entities = deduplicateBySimilarity(entities);
        
        // 3. 关系去重（确保关系中的实体都存在）
        Set<String> validNames = new HashSet<>();
        entities.forEach(e -> validNames.add(e.getName().toLowerCase().trim()));
        
        relationships = relationships.stream()
                .filter(r -> 
                        validNames.contains(r.getSource().toLowerCase().trim()) &&
                        validNames.contains(r.getTarget().toLowerCase().trim()))
                .toList();
        
        // 4. 关系精确去重
        Set<String> seenRels = new HashSet<>();
        relationships = relationships.stream()
                .filter(r -> {
                    String key = (r.getSource() + "|" + r.getTarget()).toLowerCase().trim();
                    return seenRels.add(key);
                })
                .toList();
        
        return new ExtractionResult(entities, relationships);
    }

    /**
     * 相似度去重（动态检测，不硬编码）
     */
    private List<Entity> deduplicateBySimilarity(List<Entity> entities) {
        if (entities.size() <= 1) return entities;
        
        List<Entity> deduplicated = new ArrayList<>();
        
        for (Entity entity : entities) {
            boolean isDuplicate = false;
            String currentName = entity.getName().toLowerCase().trim();
            
            for (Entity existing : deduplicated) {
                String existingName = existing.getName().toLowerCase().trim();
                
                // 检查是否相似
                if (isSimilar(currentName, existingName)) {
                    isDuplicate = true;
                    
                    // 保留更长的名称（通常更完整）
                    if (entity.getName().length() > existing.getName().length()) {
                        deduplicated.remove(existing);
                        deduplicated.add(entity);
                        log.debug("去重替换: {} -> {}", existing.getName(), entity.getName());
                    }
                    break;
                }
            }
            
            if (!isDuplicate) {
                deduplicated.add(entity);
            }
        }
        
        return deduplicated;
    }

    /**
     * 判断两个名称是否相似（动态算法，不硬编码）
     */
    private boolean isSimilar(String name1, String name2) {
        if (name1 == null || name2 == null || name1.isEmpty() || name2.isEmpty()) {
            return false;
        }
        
        // 1. 包含关系检测
        if (name1.contains(name2) || name2.contains(name1)) {
            // 如果一个是另一个的子串，检查长度差异
            int diff = Math.abs(name1.length() - name2.length());
            // 差异小于30%认为是相似
            int maxLen = Math.max(name1.length(), name2.length());
            return (double) diff / maxLen < 0.3;
        }
        
        // 2. 编辑距离检测
        double distance = levenshteinDistance(name1, name2);
        int maxLen = Math.max(name1.length(), name2.length());
        double similarity = 1 - (distance / maxLen);
        
        return similarity > 0.8; // 80% 相似度阈值
    }

    /**
     * Levenshtein 编辑距离
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;
        
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        
        return dp[s1.length()][s2.length()];
    }
}
