package com.xdu.aibot.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.std.SpringAIStateSerializer;
import com.alibaba.cloud.ai.memory.redis.RedissonRedisChatMemoryRepository;
import com.xdu.aibot.advisor.GraphRagAdvisor;
import com.xdu.aibot.constant.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.neo4j.Neo4jVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.SmartInitializingSingleton;

@Slf4j
@Configuration
@EnableConfigurationProperties(AibotProperties.class)
public class CommonConfiguration {

    private final String neo4jUri;
    private final String neo4jPassword;
    private final String neo4jUsername;
    private final String chatModelType;
    private final RedissonRedisChatMemoryRepository redisChatMemoryRepository;
    private final AibotProperties aibotProperties;

    public CommonConfiguration(@Value("${spring.neo4j.uri}") String neo4jUri,
                               @Value("${spring.neo4j.authentication.password}") String neo4jPassword,
                               @Value("${spring.neo4j.authentication.username}") String neo4jUsername,
                               @Value("${aibot.chat-model-type:cloud}") String chatModelType,
                               RedissonRedisChatMemoryRepository redisChatMemoryRepository,
                               AibotProperties aibotProperties) {
        this.neo4jUri = neo4jUri;
        this.neo4jPassword = neo4jPassword;
        this.neo4jUsername = neo4jUsername;
        this.chatModelType = chatModelType;
        this.redisChatMemoryRepository = redisChatMemoryRepository;
        this.aibotProperties = aibotProperties;
    }

    @Bean
    public SmartInitializingSingleton entityVectorIndexInitializer(Driver driver) {
        return () -> {
            AibotProperties.Graph graphConfig = aibotProperties.getGraph();
            try (var session = driver.session()) {
                session.run("""
                    CREATE VECTOR INDEX %s IF NOT EXISTS
                    FOR (n:Entity) ON (n.embedding)
                    OPTIONS {
                        indexConfig: {
                            `vector.dimensions`: %d,
                            `vector.similarity_function`: 'cosine'
                        }
                    }
                    """.formatted(graphConfig.getEntityVectorIndexName(), graphConfig.getEntityVectorDimension()));
                log.info("Entity向量索引创建成功: {}", graphConfig.getEntityVectorIndexName());
            } catch (Exception e) {
                log.error("Entity向量索引创建失败: {}", e.getMessage(), e);
            }
        };
    }

    @Bean
    @Primary
    public ChatModel selectedChatModel(OpenAiChatModel openAiChatModel, OllamaChatModel ollamaChatModel) {
        if ("ollama".equalsIgnoreCase(chatModelType)) {
            return ollamaChatModel;
        }
        return openAiChatModel;
    }

    @Bean
    public VectorStore mySimpleVectorStore(@Qualifier("openAiEmbeddingModel") OpenAiEmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    public Driver driver() {
        return GraphDatabase.driver(neo4jUri,
                AuthTokens.basic(neo4jUsername, neo4jPassword));
    }

    @Bean
    public VectorStore customNeo4jVectorStore(Driver driver, @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
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
    public ReactAgent bookAgent(ChatModel chatModel, ToolCallbackProvider tools, RedissonClient redissonClient) {
        return ReactAgent.builder()
                .name("book-assistant")
                .model(chatModel)
                .instruction(SystemConstants.SERVICE_PROMPT)
                .toolCallbackProviders(tools)
                .saver(RedisSaver.builder().redisson(redissonClient).stateSerializer(new SpringAIStateSerializer()).build())
                .enableLogging(true)
                .build();
    }

    @Bean
    public ChatClient serviceChatClient(ChatModel chatModel, ToolCallbackProvider tools) {
        return ChatClient
                .builder(chatModel)
                .defaultSystem(SystemConstants.SERVICE_PROMPT)
                .defaultToolCallbacks(tools)
                .defaultOptions(OllamaChatOptions.builder().disableThinking().build())
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().chatMemoryRepository(redisChatMemoryRepository)
                                        .maxMessages(20)
                                        .build())
                                .build()
                )
                .build();
    }

    @Bean
    public ChatClient pdfChatClient(ChatModel chatModel, @Qualifier("customNeo4jVectorStore") VectorStore vectorStore,
                                    Neo4jClient neo4jClient, Driver driver, @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
        return ChatClient
                .builder(chatModel)
                .defaultSystem("根据所给上下文及知识图谱补充信息（如有）回答问题，不要随意编造")
                .defaultOptions(OllamaChatOptions.builder().disableThinking().build())
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
                        new GraphRagAdvisor(neo4jClient, driver, embeddingModel, aibotProperties)
                )
                .build();
    }

    @Bean
    public ChatClient extractionChatClient(ChatModel chatModel) {
        return ChatClient
                .builder(chatModel)
                .defaultSystem("你是一个实体关系抽取专家。")
                .defaultOptions(OllamaChatOptions.builder().disableThinking().build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}
