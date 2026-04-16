package com.xdu.aibot;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest
class AIbotApplicationTests {

    @Autowired
    private ReactAgent bookAgent;

    @Test
    void testNeo4jConnection() {
        Driver driver = GraphDatabase.driver("neo4j://localhost:7687",
                AuthTokens.basic("neo4j", "12345678"));
        try (var session = driver.session()) {
            var result = session.run("RETURN 1 AS num");
            var record = result.single();
            log.info("Neo4j连接成功，返回值: {}", record.get("num").asInt());
        } finally {
            driver.close();
        }
    }

    @Test
    public void testMcp() throws GraphRunnerException {
        AssistantMessage response = bookAgent.call("我想借一本三体");
        log.info(response.getText());
    }

}
