package com.xdu.aibot;

import com.xdu.aibot.util.VectorDistanceUtils;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.QueryConfig;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class AIbotApplicationTests {
    @Autowired
    private OpenAiEmbeddingModel embeddingModel;
    @Autowired
    private Driver driver;

    @Test
    void contextLoads() {
        float[] v1 = embeddingModel.embed("人类");
        float[] v2 = embeddingModel.embed("失明");
        float[] v3 = embeddingModel.embed("机械");

        float[] r1 = VectorDistanceUtils.addVectors(v1, v2);
        double r = VectorDistanceUtils.cosineDistance(r1, v3);
        System.out.println(r);

    }

    @Test
    public void testEmbedding() {
        // 1.测试数据
        // 1.1.用来查询的文本，国际冲突
        String query = "苹果";

        // 1.2.用来做比较的文本
        String[] texts = new String[]{
                "梨",
                "苹果醋",
                "果树",
                "人工智能",
                "电子游戏",
        };
        // 2.向量化
        // 2.1.先将查询文本向量化
        float[] queryVector = embeddingModel.embed(query);

        // 2.2.再将比较文本向量化，放到一个数组
        List<float[]> textVectors = embeddingModel.embed(Arrays.asList(texts));

        // 3.比较欧氏距离
        // 3.1.把查询文本自己与自己比较，肯定是相似度最高的
        System.out.println(VectorDistanceUtils.euclideanDistance(queryVector, queryVector));
        // 3.2.把查询文本与其它文本比较
        for (float[] textVector : textVectors) {
            System.out.println(VectorDistanceUtils.euclideanDistance(queryVector, textVector));
        }
        System.out.println("------------------");

        // 4.比较余弦距离
        // 4.1.把查询文本自己与自己比较，肯定是相似度最高的
        System.out.println(VectorDistanceUtils.cosineDistance(queryVector, queryVector));
        // 4.2.把查询文本与其它文本比较
        for (float[] textVector : textVectors) {
            System.out.println(VectorDistanceUtils.cosineDistance(queryVector, textVector));
        }
    }

    @Test
    public void testNeo4j() {
        String dbUri = "neo4j+s://64e77422.databases.neo4j.io";
        String dbUser = "neo4j";
        String dbPassword = "a1k4DzQOZA7TPJa_KoYh8vdXnQdkS3N3QLcYDThQGVM";

        try (var driver = GraphDatabase.driver(dbUri, AuthTokens.basic(dbUser, dbPassword))) {
            driver.verifyConnectivity();
            System.out.println("Connection established.");
        }


        var result = driver.executableQuery("""
            CREATE (a:Person {name: $name})
            CREATE (b:Person {name: $friendName})
            CREATE (a)-[:KNOWS]->(b)
            """)
                .withParameters(Map.of("name", "Alice", "friendName", "David"))
                .withConfig(QueryConfig.builder().withDatabase("neo4j").build())
                .execute();

        var summary = result.summary();
        System.out.printf("Created %d records in %d ms.%n",
                summary.counters().nodesCreated(),
                summary.resultAvailableAfter(TimeUnit.MILLISECONDS));
    }

}
