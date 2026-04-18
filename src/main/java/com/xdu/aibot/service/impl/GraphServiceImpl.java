package com.xdu.aibot.service.impl;

import com.xdu.aibot.service.GraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class GraphServiceImpl implements GraphService {

    private final Neo4jClient neo4jClient;

    public GraphServiceImpl(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    public Map<String, Object> getGraphData(String chatId) {
        Map<String, Object> result = new HashMap<>();

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> nodeNames = new LinkedHashSet<>();

        neo4jClient.query("""
                MATCH (n:Entity)-[r]->(m:Entity)
                WHERE $chatId IN n.docIds AND $chatId IN m.docIds
                RETURN n.name AS source, n.entityType AS sourceType, n.description AS sourceDesc,
                       coalesce(r.name, type(r)) AS relName, type(r) AS relType,
                       m.name AS target, m.entityType AS targetType, m.description AS targetDesc
                LIMIT 1000
                """)
                .bind(chatId).to("chatId")
                .fetch()
                .all()
                .forEach(record -> {
                    String sourceName = (String) record.get("source");
                    String targetName = (String) record.get("target");

                    if (nodeNames.add(sourceName)) {
                        Map<String, Object> node = new HashMap<>();
                        node.put("name", sourceName);
                        node.put("entityType", record.get("sourceType"));
                        node.put("description", record.get("sourceDesc"));
                        nodes.add(node);
                    }
                    if (nodeNames.add(targetName)) {
                        Map<String, Object> node = new HashMap<>();
                        node.put("name", targetName);
                        node.put("entityType", record.get("targetType"));
                        node.put("description", record.get("targetDesc"));
                        nodes.add(node);
                    }

                    Map<String, Object> edge = new HashMap<>();
                    edge.put("source", sourceName);
                    edge.put("target", targetName);
                    edge.put("name", record.get("relName"));
                    edge.put("type", record.get("relType"));
                    edges.add(edge);
                });

        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }
}
