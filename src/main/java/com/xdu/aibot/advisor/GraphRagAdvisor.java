package com.xdu.aibot.advisor;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import com.xdu.aibot.config.AibotProperties;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Values;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class GraphRagAdvisor implements CallAdvisor {

    private final Neo4jClient neo4jClient;
    private final Driver driver;
    private final EmbeddingModel embeddingModel;
    private final AibotProperties aibotProperties;

    public GraphRagAdvisor(Neo4jClient neo4jClient, Driver driver, EmbeddingModel embeddingModel, AibotProperties aibotProperties) {
        this.neo4jClient = neo4jClient;
        this.driver = driver;
        this.embeddingModel = embeddingModel;
        this.aibotProperties = aibotProperties;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {

        List<Document> documents = findDocumentsInContext(request.context());

        if (documents == null || documents.isEmpty()) {
            return chain.nextCall(request);
        }

        String chatId;
        Object metadataChatId = documents.get(0).getMetadata().get("chat_id");
        if (metadataChatId == null) metadataChatId = documents.get(0).getMetadata().get("metadata.chat_id");

        if (metadataChatId != null) {
            chatId = metadataChatId.toString();
        } else {
            return chain.nextCall(request);
        }

        String mixedContent = request.prompt().getUserMessage().getText();
        String userQuestion = extractUserQuestion(mixedContent);

        Set<String> seedNames = findSeedEntities(userQuestion, chatId);
        if (seedNames.isEmpty()) {
            log.info("GraphRAG: 未找到种子实体，跳过图谱增强");
            return chain.nextCall(request);
        }

        log.info("GraphRAG: 种子实体集合: {}", seedNames);

        List<Map<String, String>> relations = multiHopTraversal(seedNames, chatId);
        if (relations.isEmpty()) {
            return chain.nextCall(request);
        }

        Set<String> entityNames = new LinkedHashSet<>();
        for (Map<String, String> rel : relations) {
            entityNames.add(rel.get("source"));
            entityNames.add(rel.get("target"));
        }

        Map<String, String> entityDescriptions = fetchEntityDescriptions(entityNames, chatId);

        String graphContext = buildStructuredContext(entityNames, entityDescriptions, relations);

        String appendText = "\n\n【知识图谱信息】\n" + graphContext;

        Prompt updatedPrompt = request.prompt().augmentUserMessage(userMessage -> {
            String originalText = userMessage.getText();
            return userMessage.mutate()
                    .text(originalText + appendText)
                    .build();
        });

        ChatClientRequest updatedRequest = ChatClientRequest.builder()
                .prompt(updatedPrompt)
                .context(Map.copyOf(request.context()))
                .build();

        log.info("GraphRAG: 已注入 {} 条图谱关系, {} 个实体", relations.size(), entityNames.size());

        return chain.nextCall(updatedRequest);
    }

    private String extractUserQuestion(String mixedContent) {
        int contextIndex = mixedContent.indexOf("Context information is below, surrounded by");
        if (contextIndex > 0) {
            return mixedContent.substring(0, contextIndex).trim();
        }
        return mixedContent;
    }

    private Set<String> findSeedEntities(String userQuestion, String chatId) {
        Set<String> seeds = new LinkedHashSet<>();

        List<String> vectorSeeds = vectorSearchSeeds(userQuestion, chatId);
        seeds.addAll(vectorSeeds);

        List<String> keywordSeeds = hanlpKeywordSeeds(userQuestion, chatId);
        seeds.addAll(keywordSeeds);

        return seeds;
    }

    private List<String> vectorSearchSeeds(String userQuestion, String chatId) {
        AibotProperties.Graph config = aibotProperties.getGraph();
        List<String> results = new ArrayList<>();

        try {
            float[] queryVector = embeddingModel.embed(userQuestion);

            String cypher = """
                CALL db.index.vector.queryNodes($indexName, $topK, $queryVector)
                YIELD node, score
                WHERE score >= $threshold AND $chatId IN node.docIds
                RETURN node.name AS name
                """;

            try (var session = driver.session()) {
                var records = session.run(cypher, Map.of(
                        "indexName", config.getEntityVectorIndexName(),
                        "topK", config.getSearchTopK(),
                        "queryVector", Values.value(queryVector),
                        "threshold", config.getSearchSimilarityThreshold(),
                        "chatId", chatId
                )).list();

                for (Record record : records) {
                    String name = record.get("name").asString();
                    results.add(name);
                }
            }

            log.debug("向量检索种子: {}", results);
        } catch (Exception e) {
            log.warn("向量检索种子节点失败，降级为仅关键词检索: {}", e.getMessage());
        }

        return results;
    }

    private List<String> hanlpKeywordSeeds(String userQuestion, String chatId) {
        List<Term> terms = HanLP.segment(userQuestion);
        List<String> keywords = terms.stream()
                .filter(term -> {
                    String nature = term.nature.toString();
                    return nature.startsWith("nr") || nature.startsWith("ns") || nature.startsWith("nt") || nature.equals("n");
                })
                .map(term -> term.word)
                .filter(word -> word.length() > 1)
                .collect(Collectors.toList());

        if (keywords.isEmpty()) return List.of();

        List<String> matched = neo4jClient.query(
                "MATCH (n:Entity) WHERE n.name IN $keywords AND $chatId IN n.docIds RETURN n.name AS name"
        )
                .bindAll(Map.of("keywords", keywords, "chatId", chatId))
                .fetchAs(String.class)
                .all()
                .stream()
                .toList();

        log.debug("HanLP关键词种子: keywords={}, matched={}", keywords, matched);
        return matched;
    }

    private List<Map<String, String>> multiHopTraversal(Set<String> seedNames, String chatId) {
        AibotProperties.Graph config = aibotProperties.getGraph();
        int depth = config.getTraversalDepth();
        int limit = config.getTraversalLimit();

        String cypher = String.format("""
                MATCH path = (seed:Entity)-[r*1..%d]-(target:Entity)
                WHERE seed.name IN $seedNames AND $chatId IN seed.docIds
                  AND ALL(n IN nodes(path) WHERE $chatId IN n.docIds)
                WITH DISTINCT relationships(path) AS rels
                UNWIND rels AS rel
                WITH DISTINCT rel
                RETURN startNode(rel).name AS source,
                       coalesce(rel.name, type(rel)) AS relName,
                       endNode(rel).name AS target
                LIMIT %d
                """, depth, limit);

        List<Map<String, String>> relations = new ArrayList<>();

        try {
            List<Map<String, String>> results = neo4jClient.query(cypher)
                    .bindAll(Map.of("seedNames", new ArrayList<>(seedNames), "chatId", chatId))
                    .fetch()
                    .all()
                    .stream()
                    .map(record -> {
                        Map<String, String> map = new HashMap<>();
                        map.put("source", (String) record.get("source"));
                        map.put("relName", (String) record.get("relName"));
                        map.put("target", (String) record.get("target"));
                        return map;
                    })
                    .toList();

            relations.addAll(results);
        } catch (Exception e) {
            log.warn("多跳遍历失败: {}", e.getMessage());
        }

        return relations;
    }

    private Map<String, String> fetchEntityDescriptions(Set<String> entityNames, String chatId) {
        Map<String, String> descriptions = new LinkedHashMap<>();

        try {
            neo4jClient.query(
                    "MATCH (n:Entity) WHERE n.name IN $names AND $chatId IN n.docIds RETURN n.name AS name, n.entityType AS entityType, n.description AS description"
            )
                    .bindAll(Map.of("names", new ArrayList<>(entityNames), "chatId", chatId))
                    .fetch()
                    .all()
                    .forEach(record -> {
                        String name = (String) record.get("name");
                        String entityType = (String) record.get("entityType");
                        String desc = (String) record.get("description");
                        String display = entityType != null ? name + "(" + translateType(entityType) + ")" : name;
                        if (desc != null && !desc.isBlank()) {
                            display += ": " + desc;
                        }
                        descriptions.put(name, display);
                    });
        } catch (Exception e) {
            log.warn("获取实体描述失败: {}", e.getMessage());
            for (String name : entityNames) {
                descriptions.put(name, name);
            }
        }

        return descriptions;
    }

    private String buildStructuredContext(Set<String> entityNames, Map<String, String> entityDescriptions, List<Map<String, String>> relations) {
        StringBuilder sb = new StringBuilder();

        sb.append("相关实体:\n");
        for (String name : entityNames) {
            String desc = entityDescriptions.getOrDefault(name, name);
            sb.append("  - ").append(desc).append("\n");
        }

        sb.append("\n实体关系:\n");
        for (Map<String, String> rel : relations) {
            sb.append("  - ")
                    .append(rel.get("source"))
                    .append(" --[").append(rel.get("relName")).append("]--> ")
                    .append(rel.get("target"))
                    .append("\n");
        }

        return sb.toString();
    }

    private String translateType(String entityType) {
        if (entityType == null) return "其他";
        return switch (entityType) {
            case "Person" -> "人物";
            case "Organization" -> "组织";
            case "Location" -> "地点";
            case "Concept" -> "概念";
            case "Event" -> "事件";
            case "Technology" -> "技术";
            case "Product" -> "产品";
            case "Time" -> "时间";
            default -> "其他";
        };
    }

    private List<Document> findDocumentsInContext(Map<String, Object> context) {
        if (context.containsKey("qa_retrieved_documents")) {
            return (List<Document>) context.get("qa_retrieved_documents");
        }
        if (context.containsKey("rag_document_context")) {
            return (List<Document>) context.get("rag_document_context");
        }
        return null;
    }
}
