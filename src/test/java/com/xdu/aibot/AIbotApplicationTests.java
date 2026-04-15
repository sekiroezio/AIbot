package com.xdu.aibot;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.QueryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class AIbotApplicationTests {
    private static final Logger log = LoggerFactory.getLogger(AIbotApplicationTests.class);
    @Autowired
    private OpenAiEmbeddingModel embeddingModel;
    @Autowired
    private Driver driver;
    @Qualifier("serviceChatClient")
    @Autowired
    private ChatClient serviceChatClient;

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

    @Test
    public void testMcp() {
        String response = serviceChatClient
                .prompt()
                .user("我想看科幻小说")
                .call()
                .content();
        log.info(response);
    }

}
