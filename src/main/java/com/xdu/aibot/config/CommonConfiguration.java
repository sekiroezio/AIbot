package com.xdu.aibot.config;

import com.alibaba.cloud.ai.memory.redis.RedissonRedisChatMemoryRepository;
import com.xdu.aibot.advisor.GraphRagAdvisor;
import com.xdu.aibot.constant.SystemConstants;
import com.xdu.aibot.tools.BookTools;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.neo4j.Neo4jVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.core.Neo4jClient;

@Configuration
public class CommonConfiguration {

    @Value("${spring.neo4j.uri}")
    private String neo4jUri;
    @Value("${spring.neo4j.authentication.password}")
    private String neo4jPassword;
    @Value("${spring.neo4j.authentication.username}")
    private String neo4jUsername;

    @Value("${aibot.chat-model-type:cloud}")
    private String chatModelType;

    @Value("${aibot.embedding-model-type:cloud}")
    private String embeddingModelType;

    @Autowired
    private RedissonRedisChatMemoryRepository redisChatMemoryRepository;

    @Bean
    @Primary
    public ChatModel selectedChatModel(OpenAiChatModel openAiChatModel, OllamaChatModel ollamaChatModel) {
        if ("ollama".equalsIgnoreCase(chatModelType)) {
            return ollamaChatModel;
        }
        return openAiChatModel;
    }

    @Bean
    @Primary
    public EmbeddingModel selectedEmbeddingModel(OpenAiEmbeddingModel openAiEmbeddingModel, OllamaEmbeddingModel ollamaEmbeddingModel) {
        if ("ollama".equalsIgnoreCase(embeddingModelType)) {
            return ollamaEmbeddingModel;
        }
        return openAiEmbeddingModel;
    }

    @Bean
    public VectorStore mySimpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    public Driver driver() {
        return GraphDatabase.driver(neo4jUri,
                AuthTokens.basic(neo4jUsername, neo4jPassword));
    }

    @Bean
    public VectorStore customNeo4jVectorStore(Driver driver, EmbeddingModel embeddingModel) {
        return Neo4jVectorStore.builder(driver, embeddingModel)
                .databaseName("neo4j")
                .distanceType(Neo4jVectorStore.Neo4jDistanceType.COSINE)
                .embeddingDimension(1536)
                .label("Document")
                .embeddingProperty("embedding")
                .indexName("custom-index")
                .initializeSchema(true)
                .batchingStrategy(new TokenCountBatchingStrategy())
                .build();
    }


    @Bean
    public ChatClient serviceChatClient(ChatModel chatModel, BookTools courseTools) {

        return ChatClient
                .builder(chatModel)
                .defaultSystem(SystemConstants.SERVICE_PROMPT)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().chatMemoryRepository(redisChatMemoryRepository)
                                        .maxMessages(20)
                                        .build())
                                .build()
                )
                .defaultTools(courseTools)
                .build();
    }

    @Bean
    public ChatClient pdfChatClient(ChatModel chatModel, @Qualifier("customNeo4jVectorStore") VectorStore vectorStore, Neo4jClient neo4jClient) {

        return ChatClient
                .builder(chatModel)
                .defaultSystem("根据所给上下文回答问题，不要随意编造")
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().chatMemoryRepository(redisChatMemoryRepository)
                                        .maxMessages(20)
                                        .build())
                                .build(),
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(
                                        SearchRequest.builder()
                                                .similarityThreshold(0.2d)
                                                .topK(3)
                                                .build()
                                ).build(),
                        new GraphRagAdvisor(neo4jClient),
                        new CallAdvisor() {
                            @Override
                            public String getName() { return "TrapAdvisor"; }
                            @Override
                            public int getOrder() { return 2; }

                            @Override
                            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                                String lastPrompt = request.prompt().getUserMessage().getText();
                                System.out.println("====== 增强后的最终 Prompt ======");
                                System.out.println(lastPrompt);
                                System.out.println("==================================");
                                return chain.nextCall(request);
                            }
                        }
                )
                .build();
    }

    @Bean
    public ChatClient extractionChatClient(ChatModel chatModel) {
        return ChatClient
                .builder(chatModel)
                .defaultSystem("你是一个实体关系抽取专家。")
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}
