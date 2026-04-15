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
        String userQuestion = mixedContent;
        int contextIndex = mixedContent.indexOf("Context information is below, surrounded by");
        if (contextIndex > 0) {
            userQuestion = mixedContent.substring(0, contextIndex).trim();
        }

        List<Term> terms = HanLP.segment(userQuestion);
        List<String> keywords = terms.stream()
                .filter(term -> {
                    String nature = term.nature.toString();
                    return nature.startsWith("nr") || nature.startsWith("ns") || nature.startsWith("nt") || nature.equals("n");
                })
                .map(term -> term.word)
                .filter(word -> word.length() > 1)
                .collect(Collectors.toList());
        log.info("用户问题: [{}], HanLP提取关键词: {}", userQuestion, keywords);
        if (keywords.isEmpty()) {
            return chain.nextCall(request);
        }

        String cypher = """
            MATCH (anchor:Entity)
            WHERE anchor.name IN $keywords AND $chatId IN anchor.docIds
            MATCH (anchor)-[r]-(neighbor:Entity)
            WHERE $chatId IN neighbor.docIds
            RETURN anchor.name + '(' + anchor.entityType + ') -[' + coalesce(r.name, type(r)) + ']-> ' + neighbor.name + '(' + neighbor.entityType + ')' AS relation
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

        log.info("GraphRAG Advisor: 已注入 {} 条图谱关系", relations.size());
        log.info("查询到的图谱信息: {}", graphContext);

        return chain.nextCall(updatedRequest);
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
