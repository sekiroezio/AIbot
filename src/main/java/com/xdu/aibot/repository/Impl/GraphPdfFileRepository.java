package com.xdu.aibot.repository.Impl;

import com.xdu.aibot.pojo.entity.Entity;
import com.xdu.aibot.pojo.entity.Relationship;
import com.xdu.aibot.repository.FileRepository;
import com.xdu.aibot.service.ExtractionResult;
import com.xdu.aibot.service.LLMEntityExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PDF 文件仓库 - 使用 LLM 进行实体关系抽取
 */
@Slf4j
@Service("graphPdfFileRepository")
@RequiredArgsConstructor
public class GraphPdfFileRepository implements FileRepository {

    @Autowired
    @Qualifier("customNeo4jVectorStore")
    private VectorStore vectorStore;

    private final Neo4jClient neo4jClient;
    
    @Autowired
    private LLMEntityExtractor llmEntityExtractor;

    @Override
    public boolean save(String chatId, Resource resource) {
        // 1. 本地文件备份
        String filename = resource.getFilename();
        File target = new File(Objects.requireNonNull(filename));
        if (!target.exists()) {
            try {
                Files.copy(resource.getInputStream(), target.toPath());
            } catch (IOException e) {
                log.error("Failed to save PDF resource.", e);
                return false;
            }
        }

        // 2. 解析PDF
        List<Document> documents = parsePdf(resource, chatId);

        // 3. 写入向量库
        try {
            vectorStore.add(documents);
        } catch (Exception e) {
            log.error("向量库写入失败", e);
            return false;
        }

        // 4. 使用 LLM 构建知识图谱（不使用 pdfChatClient，避免注入提示词）
        buildKnowledgeGraphWithLLM(documents, chatId);

        return true;
    }

    @Override
    public Resource getFile(String chatId) {
        String fileName = neo4jClient.query("MATCH (d:Document) WHERE d.`metadata.chat_id` = $chatId RETURN d.`metadata.file_name` AS filename LIMIT 1")
                .bind(chatId).to("chatId")
                .fetchAs(String.class).one().orElse(null);

        if (fileName != null) {
            return new FileSystemResource(fileName);
        }
        return null;
    }

    /**
     * 解析 PDF 为文档列表
     */
    private List<Document> parsePdf(Resource resource, String chatId) {
        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                resource,
                PdfDocumentReaderConfig.builder()
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                        .withPagesPerDocument(1)
                        .build()
        );
        List<Document> documents = reader.read();
        documents.forEach(document -> {
            document.getMetadata().put("chat_id", chatId);
            document.getMetadata().put("file_name", resource.getFilename());
        });
        return documents;
    }

    /**
     * 使用 LLM 动态构建知识图谱（不硬编码 Schema）
     */
    private void buildKnowledgeGraphWithLLM(List<Document> documents, String chatId) {
        log.info("开始构建知识图谱 (LLM模式): {}", chatId);

        // 1. 创建 SourceFile 节点
        try {
            neo4jClient.query("MERGE (s:SourceFile {chatId: $chatId})")
                    .bind(chatId).to("chatId").run();
        } catch (Exception e) {
            log.error("SourceFile 节点创建失败", e);
            return;
        }

        // 2. 准备文本块
        TokenTextSplitter splitter = new TokenTextSplitter(400, 300, 10, 50, true);
        List<String> chunks = documents.stream()
                .map(doc -> {
                    String content = doc.getText();
                    // 移除多余空白
                    return content.replaceAll("(?<=[\\u4e00-\\u9fa5])\\s+(?=[\\u4e00-\\u9fa5])", "");
                })
                .filter(text -> text.length() > 50) // 过滤太短的文本
                .collect(Collectors.toList());

        log.info("准备抽取的文本块数量: {}", chunks.size());

        // 3. 使用 LLM 批量抽取实体关系
        ExtractionResult result = llmEntityExtractor.extractBatch(chunks);
        
        log.info("LLM 抽取结果: {} 个实体, {} 个关系", 
                result.getEntities().size(), 
                result.getRelationships().size());

        // 4. 保存到 Neo4j
        saveEntitiesAndRelationships(result, chatId);
        
        log.info("知识图谱构建完成!");
    }

    /**
     * 保存实体和关系到 Neo4j
     */
    private void saveEntitiesAndRelationships(ExtractionResult result, String chatId) {
        Set<String> createdEntities = new HashSet<>();

        // 1. 保存实体
        for (Entity entity : result.getEntities()) {
            String name = entity.getName().trim();
            if (name.isEmpty() || createdEntities.contains(name.toLowerCase())) {
                continue;
            }
            createdEntities.add(name.toLowerCase());

            String safeType = entity.getType().replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "");
            if (safeType.isEmpty()) safeType = "Entity";

            try {
                String cypher = String.format("""
                    MERGE (n:`%s` {name: $name})
                    SET n.description = $description
                    MERGE (s:SourceFile {chatId: $chatId})
                    MERGE (n)-[:MENTIONED_IN]->(s)
                    """, safeType);

                neo4jClient.query(cypher)
                        .bind(name).to("name")
                        .bind(entity.getDescription()).to("description")
                        .bind(chatId).to("chatId")
                        .run();
                        
                log.debug("实体创建: {}", name);
            } catch (Exception e) {
                log.warn("实体写入失败: {}", name, e);
            }
        }

        // 2. 保存关系
        for (Relationship rel : result.getRelationships()) {
            String source = rel.getSource().trim();
            String target = rel.getTarget().trim();

            // 确保实体存在
            if (!createdEntities.contains(source.toLowerCase()) || 
                !createdEntities.contains(target.toLowerCase())) {
                log.debug("跳过关系，实体不存在: {} -> {}", source, target);
                continue;
            }

            // 使用标准化的关系类型
            String relType = normalizeRelationType(rel.getType());
            String relDesc = rel.getDescription();

            try {
                // 创建标准关系类型
                String cypher = String.format("""
                    MATCH (a), (b)
                    WHERE a.name = $source AND b.name = $target
                    MERGE (a)-[:`%s`]->(b)
                    """, relType);

                neo4jClient.query(cypher)
                        .bind(source).to("source")
                        .bind(target).to("target")
                        .run();
                        
                log.debug("关系创建: {} -[{}]-> {}", source, relType, target);
            } catch (Exception e) {
                log.warn("关系写入失败: {} -> {}, 错误: {}", source, target, e.getMessage());
            }
        }
    }

    /**
     * 标准化关系类型（动态检测，不硬编码）
     */
    private String normalizeRelationType(String type) {
        if (type == null || type.isBlank()) return "相关";
        
        type = type.trim();
        
        // 动态检测关系类型关键词
        if (type.contains("创造") || type.contains("创作") || type.contains("写") || type.contains("著")) {
            return "创作";
        }
        if (type.contains("是") || type.contains("为")) {
            return "是";
        }
        if (type.contains("属于") || type.contains("归")) {
            return "属于";
        }
        if (type.contains("位于") || type.contains("坐落") || type.contains("在")) {
            return "位于";
        }
        if (type.contains("学习") || type.contains("就读") || type.contains("上")) {
            return "学习";
        }
        if (type.contains("教导") || type.contains("教") || type.contains("指导")) {
            return "教导";
        }
        if (type.contains("管理") || type.contains("领导") || type.contains("校长") || type.contains("长")) {
            return "管理";
        }
        if (type.contains("参与") || type.contains("参加")) {
            return "参与";
        }
        if (type.contains("帮助") || type.contains("帮")) {
            return "帮助";
        }
        if (type.contains("反对") || type.contains("对抗") || type.contains("敌")) {
            return "反对";
        }
        if (type.contains("朋友") || type.contains("友")) {
            return "朋友";
        }
        if (type.contains("家") || type.contains("父") || type.contains("母") || type.contains("子") || type.contains("兄")) {
            return "家人";
        }
        if (type.contains("夫妻") || type.contains("配偶")) {
            return "夫妻";
        }
        if (type.contains("师生") || type.contains("老师")) {
            return "师生";
        }
        if (type.contains("发生") || type.contains("爆发")) {
            return "发生";
        }
        
        // 默认返回"相关"
        return "相关";
    }
}
