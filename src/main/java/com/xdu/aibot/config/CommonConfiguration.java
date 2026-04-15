package com.xdu.aibot.config;

import com.alibaba.cloud.ai.memory.redis.RedissonRedisChatMemoryRepository;
import com.xdu.aibot.advisor.GraphRagAdvisor;
import com.xdu.aibot.constant.SystemConstants;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.neo4j.Neo4jVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.beans.factory.SmartInitializingSingleton;

@Slf4j
@Configuration
@EnableConfigurationProperties(AibotProperties.class)
public class CommonConfiguration {

    @Value("${spring.neo4j.uri}")
    private String neo4jUri;
    @Value("${spring.neo4j.authentication.password}")
    private String neo4jPassword;
    @Value("${spring.neo4j.authentication.username}")
    private String neo4jUsername;

    @Value("${aibot.chat-model-type:cloud}")
    private String chatModelType;

    @Autowired
    private RedissonRedisChatMemoryRepository redisChatMemoryRepository;

    @Autowired
    private AibotProperties aibotProperties;

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
    public VectorStore mySimpleVectorStore(@Qualifier("openAiEmbeddingModel")OpenAiEmbeddingModel embeddingModel) {
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
    public ChatClient serviceChatClient(ChatModel chatModel, ToolCallbackProvider tools) {

        return ChatClient
                .builder(chatModel)
//                .defaultSystem(SystemConstants.SERVICE_PROMPT)
                .defaultSystem(".defaultSystem(\"\"\"\n" +
                        "    你是图书馆智能借阅助手\"小图\"。你必须按 ReAct 模式工作：\n" +
                        "    \n" +
                        "    1. 思考(Reason)：分析用户需求，判断需要哪些工具\n" +
                        "    2. 行动(Act)：调用工具获取信息\n" +
                        "    3. 观察(Observe)：分析工具返回结果，判断是否需要继续调用\n" +
                        "    4. 重复以上步骤直到可以回答用户\n" +
                        "    \n" +
                        "    可用工具：\n" +
                        "    - 查询书籍信息：查询馆藏图书（按类型/作者/书名/评分/库存）\n" +
                        "    - 预约图书：预约借书（需姓名+手机号）\n" +
                        "    - tavily-search：网络搜索（查豆瓣评分、书评等外部信息）\n" +
                        "    \n" +
                        "    示例推理链：\n" +
                        "    用户：\"想借豆瓣评分最高的欧美文学\"\n" +
                        "    → 思考：需要先查馆藏欧美文学，再搜豆瓣评分\n" +
                        "    → 行动：调用\"查询书籍信息\"(type=欧美文学)\n" +
                        "    → 观察：得到3本书：《xxx》《yyy》《zzz》\n" +
                        "    → 行动：调用\"tavily-search\"搜索每本书的豆瓣评分\n" +
                        "    → 观察：《xxx》9.2分，《yyy》8.5分，《zzz》7.8分\n" +
                        "    → 回答：推荐《xxx》，豆瓣9.2分\n" +
                        "    \"\"\")")
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
                        new GraphRagAdvisor(neo4jClient, driver, embeddingModel, aibotProperties),
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
                .defaultOptions(OllamaChatOptions.builder().disableThinking().build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}
