package com.xdu.aibot.controller;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@Slf4j
@RequestMapping("/ai")
public class StreamController {

    private final ReactAgent bookAgent;

    public StreamController(ReactAgent bookAgent) {
        this.bookAgent = bookAgent;
    }

    @GetMapping(
            value = {"/chat"},
            produces = {"text/html;charset=utf-8"}
    )
    public Flux<String> chat(String prompt) throws GraphRunnerException {
        RunnableConfig config = RunnableConfig.builder().threadId("test-thread").build();
        return this.bookAgent.stream(prompt, config).doOnNext((output) -> {
            if (output instanceof StreamingOutput<?> streaming) {
                String chunk = streaming.chunk();
                if (chunk != null && !chunk.isEmpty()) {
                    log.info("STREAMING chunk: {}", chunk);
                }
            } else if (output.isEND()) {
                log.info("END");
            }

        }).map((output) -> {
            if (!(output instanceof StreamingOutput<?> streaming)) {
                return "";
            } else {
                String chunk = streaming.chunk();
                return chunk != null && !chunk.isEmpty() ? chunk : "";
            }
        }).filter((chunk) -> !chunk.isEmpty());
    }

}
