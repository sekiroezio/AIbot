package com.xdu.aibot.repository.Impl;

import com.xdu.aibot.config.AibotProperties;
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

    @Autowired
    private AibotProperties aibotProperties;

    @Override
    public boolean save(String chatId, Resource resource) {
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

        List<Document> documents = parsePdf(resource, chatId);

        try {
            vectorStore.add(documents);
        } catch (Exception e) {
            log.error("向量库写入失败", e);
            return false;
        }

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

    private void buildKnowledgeGraphWithLLM(List<Document> documents, String chatId) {
        log.info("开始构建知识图谱 (LLM模式): {}", chatId);

        TokenTextSplitter splitter = new TokenTextSplitter(400, 300, 10, 50, true);
        List<String> chunks = documents.stream()
                .map(doc -> {
                    String content = doc.getText();
                    return content.replaceAll("(?<=[\\u4e00-\\u9fa5])\\s+(?=[\\u4e00-\\u9fa5])", "");
                })
                .filter(text -> text.length() > 50)
                .collect(Collectors.toList());

        log.info("准备抽取的文本块数量: {}", chunks.size());

        ExtractionResult result = llmEntityExtractor.extractBatch(chunks);

        log.info("LLM 抽取结果: {} 个实体, {} 个关系",
                result.getEntities().size(),
                result.getRelationships().size());

        saveEntitiesAndRelationships(result, chatId);

        log.info("知识图谱构建完成!");
    }

    private void saveEntitiesAndRelationships(ExtractionResult result, String chatId) {
        Set<String> createdEntities = new HashSet<>();

        for (Entity entity : result.getEntities()) {
            String name = entity.getName().trim();
            if (name.isEmpty() || createdEntities.contains(name.toLowerCase())) {
                continue;
            }
            createdEntities.add(name.toLowerCase());

            String entityType = entity.getType() != null ? entity.getType() : "Other";
            String description = entity.getDescription() != null ? entity.getDescription() : "";

            try {
                neo4jClient.query("""
                        MERGE (n:Entity {name: $name})
                        ON CREATE SET n.entityType = $entityType, n.description = $description, n.docIds = [$chatId]
                        ON MATCH SET n.description = CASE WHEN $description <> '' AND n.description IS NULL THEN $description ELSE n.description END,
                                    n.docIds = CASE WHEN $chatId IN n.docIds THEN n.docIds ELSE n.docIds + $chatId END
                        """)
                        .bind(name).to("name")
                        .bind(entityType).to("entityType")
                        .bind(description).to("description")
                        .bind(chatId).to("chatId")
                        .run();

                log.debug("实体创建/更新: {} ({})", name, entityType);
            } catch (Exception e) {
                log.warn("实体写入失败: {}", name, e);
            }
        }

        for (Relationship rel : result.getRelationships()) {
            String source = rel.getSource().trim();
            String target = rel.getTarget().trim();

            if (!createdEntities.contains(source.toLowerCase()) ||
                !createdEntities.contains(target.toLowerCase())) {
                log.debug("跳过关系，实体不存在: {} -> {}", source, target);
                continue;
            }

            String relType = aibotProperties.mapRelationType(rel.getType());
            String relName = rel.getName() != null ? rel.getName() : relType;
            String relDesc = rel.getDescription() != null ? rel.getDescription() : "";

            try {
                String cypher = String.format("""
                        MATCH (a:Entity {name: $source}), (b:Entity {name: $target})
                        MERGE (a)-[r:`%s`]->(b)
                        SET r.name = $relName, r.description = $relDesc
                        """, relType);

                neo4jClient.query(cypher)
                        .bind(source).to("source")
                        .bind(target).to("target")
                        .bind(relName).to("relName")
                        .bind(relDesc).to("relDesc")
                        .run();

                log.debug("关系创建: {} -[{}]-> {}", source, relType, target);
            } catch (Exception e) {
                log.warn("关系写入失败: {} -> {}, 错误: {}", source, target, e.getMessage());
            }
        }
    }
}
