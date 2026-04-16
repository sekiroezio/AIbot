package com.xdu.aibot.controller;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping("/ai/stream")
@RequiredArgsConstructor
public class StreamController {

    private final ReactAgent bookAgent;

    @GetMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(String prompt) throws GraphRunnerException {
        RunnableConfig config = RunnableConfig.builder()
                .threadId("test-thread")
                .build();

        return bookAgent.stream(prompt, config)
                .doOnNext(output -> {
                    if (output instanceof StreamingOutput<?> streaming) {
                        String chunk = streaming.chunk();
                        if (chunk != null && !chunk.isEmpty()) {
                            log.info("STREAMING chunk: {}", chunk);
                        }
                    } else if (output.isEND()) {
                        log.info("END");
                    }
                })
                .<String>map(output -> {
                    if (output instanceof StreamingOutput<?> streaming) {
                        String chunk = streaming.chunk();
                        return chunk != null && !chunk.isEmpty() ? chunk : "";
                    }
                    return "";
                })
                .filter(chunk -> !chunk.isEmpty());
    }
}
