package com.xdu.aibot.controller;

import com.xdu.aibot.service.GraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ai/graph")
public class GraphController {

    private final GraphService graphService;

    public GraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    @GetMapping("/{chatId}")
    public Map<String, Object> getGraphData(@PathVariable String chatId) {
        return graphService.getGraphData(chatId);
    }
}
