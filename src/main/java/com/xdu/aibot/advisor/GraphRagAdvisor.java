package com.xdu.aibot.advisor;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.data.neo4j.core.Neo4jClient;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class GraphRagAdvisor implements CallAdvisor {

    private final Neo4jClient neo4jClient;

    public GraphRagAdvisor(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        // 必须 > 0，确保在 QuestionAnswerAdvisor 之后执行
        return 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {

        // 1. 获取文档
        List<Document> documents = findDocumentsInContext(request.context());

        // null and empty check
        if (documents == null || documents.isEmpty()) {
            return chain.nextCall(request);
        }

        // 2. 提取 chatId
        String chatId;
        Object metadataChatId = documents.get(0).getMetadata().get("chat_id");
        // 兼容带点和不带点的情况
        if (metadataChatId == null) metadataChatId = documents.get(0).getMetadata().get("metadata.chat_id");

        if (metadataChatId != null) {
            chatId = metadataChatId.toString();
        } else {
            return chain.nextCall(request);
        }


        String mixedContent = request.prompt().getUserMessage().getText();
        String userQuestion = mixedContent;
        //去除springai注入的上下文
        int contextIndex = mixedContent.indexOf("Context information is below, surrounded by");
        if (contextIndex > 0) {
            userQuestion = mixedContent.substring(0, contextIndex).trim();
        }
        // 1. 分词 + 词性标注
        List<Term> terms = HanLP.segment(userQuestion);
        // 2. 过滤：只保留名词相关的实词 (nr=人名, ns=地名, nt=机构, n=普通名词, nz=其他专名)
        List<String> keywords = terms.stream()
                .filter(term -> {
                    String nature = term.nature.toString();
                    return nature.startsWith("nr") || nature.startsWith("ns") || nature.startsWith("nt") || nature.equals("n");
                })
                .map(term -> term.word)
                // 过滤掉单字（可选，防止匹配太多）
                .filter(word -> word.length() > 1)
                .collect(Collectors.toList());
        log.info("用户问题: [{}], HanLP提取关键词: {}", userQuestion, keywords);
        if (keywords.isEmpty()) {
            return chain.nextCall(request);
        }


        // 逻辑：找到 SourceFile 下，名字包含在关键词列表里的实体，并扩展一跳
        String cypher = """
            MATCH (s:SourceFile {chatId: $chatId})
            MATCH (s)-[:MENTIONED_IN]-(anchor)
            WHERE anchor.name IN $keywords
            MATCH (anchor)-[r]-(neighbor)
            WHERE neighbor <> s
            RETURN anchor.name + ' ' + type(r) + ' ' + neighbor.name AS relation
            LIMIT 30
        """;

        List<String> relations = neo4jClient.query(cypher)
                .bindAll(Map.of(
                        "chatId", chatId,
                        "keywords", keywords
                ))
                .fetchAs(String.class)
                .all()
                .stream()
                .toList();

        if (relations.isEmpty()) {
            return chain.nextCall(request);
        }

        String graphContext = String.join("\n", relations);
        String appendText = "\n\n【知识图谱补充信息】:\n" + graphContext;

        // 修改最后一条 UserMessage
        Prompt updatedPrompt = request.prompt().augmentUserMessage(userMessage -> {
            // 获取原始文本
            String originalText = userMessage.getText();
            // 返回修改后的 UserMessage (使用 mutate 保留 media 等其他属性)
            return userMessage.mutate()
                    .text(originalText + appendText)
                    .build();
        });


        // 7. 重建 Request 并清理 Context
        ChatClientRequest updatedRequest = ChatClientRequest.builder()
                .prompt(updatedPrompt)
                .context(Map.copyOf(request.context()))
                .build();

        log.info("GraphRAG Advisor: 已注入 {} 条图谱关系", relations.size());
        log.info("查询到的图谱信息: {}", graphContext);

        return chain.nextCall(updatedRequest);
    }

    private List<Document> findDocumentsInContext(Map<String, Object> context) {
        // 尝试获取 QuestionAnswerAdvisor 放入的文档
        if (context.containsKey("qa_retrieved_documents")) {
            return (List<Document>) context.get("qa_retrieved_documents");
        }
        // 兼容其他可能的 Key (如 RetrievalAugmentationAdvisor)
        if (context.containsKey("rag_document_context")) {
            return (List<Document>) context.get("rag_document_context");
        }
        return null;
    }
}