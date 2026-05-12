package com.xdu.aibot.service.impl;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.xdu.aibot.constant.ChatType;
import com.xdu.aibot.repository.ChatHistoryRepository;
import com.xdu.aibot.service.PersonalAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class PersonalAgentImpl implements PersonalAgent {

    private static final String CHECKPOINT_PREFIX = "graph:checkpoint:content:";
    private static final String THREAD_META_PREFIX = "graph:thread:meta:";
    private static final String THREAD_REVERSE_PREFIX = "graph:thread:reverse:";

    private final ReactAgent bookAgent;
    private final ObjectMapper objectMapper;
    private final ChatHistoryRepository chatHistoryRepository;
    private final RedissonClient redissonClient;

    public PersonalAgentImpl(ReactAgent bookAgent,
                             ObjectMapper objectMapper,
                             @Qualifier("chatHistoryServiceImpl") ChatHistoryRepository chatHistoryRepository,
                             RedissonClient redissonClient) {
        this.bookAgent = bookAgent;
        this.objectMapper = objectMapper;
        this.chatHistoryRepository = chatHistoryRepository;
        this.redissonClient = redissonClient;
    }

    @Override
    public String chat(String prompt, String chatId) {
        chatHistoryRepository.save(ChatType.SERVICE.getType(), chatId);
        try {
            AssistantMessage response = bookAgent.call(prompt);
            return response.getText();
        } catch (GraphRunnerException e) {
            log.error("Agent调用失败: {}", e.getMessage(), e);
            return "抱歉，处理您的问题时出错了，请稍后再试～";
        }
    }

    @Override
    public Flux<String> chatStream(String prompt, String chatId) throws GraphRunnerException {
        chatHistoryRepository.save(ChatType.SERVICE.getType(), chatId);

        StringBuilder textBuffer = new StringBuilder();
        AtomicReference<Map<String, Map<String, String>>> pendingToolCalls = new AtomicReference<>(new LinkedHashMap<>());

        return bookAgent.stream(prompt, RunnableConfig.builder().threadId(chatId).build())
                .flatMap(nodeOutput -> {
                    if (!(nodeOutput instanceof StreamingOutput<?> streamingOutput)) {
                        return Flux.empty();
                    }

                    OutputType outputType = streamingOutput.getOutputType();
                    if (outputType == null) return Flux.empty();

                    List<String> sseEvents = new ArrayList<>();

                    switch (outputType) {
                        case AGENT_MODEL_STREAMING -> {
                            String chunk = streamingOutput.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                textBuffer.append(chunk);
                                sseEvents.add(sseData(Map.of("type", "token", "content", chunk)));
                            }
                        }
                        case AGENT_MODEL_FINISHED -> {
                            var message = streamingOutput.message();
                            if (message instanceof AssistantMessage assistantMsg) {
                                if (assistantMsg.hasToolCalls()) {
                                    String thinkingText = textBuffer.toString().trim();
                                    textBuffer.setLength(0);
                                    if (!thinkingText.isEmpty()) {
                                        sseEvents.add(sseData(Map.of("type", "thinking", "content", thinkingText)));
                                    }

                                    Map<String, Map<String, String>> pending = pendingToolCalls.get();
                                    for (var toolCall : assistantMsg.getToolCalls()) {
                                        String callId = toolCall.id() != null ? toolCall.id() : UUID.randomUUID().toString();
                                        String toolName = toolCall.name();
                                        String toolArgs;
                                        try {
                                            Object parsed = objectMapper.readValue(toolCall.arguments(), Object.class);
                                            toolArgs = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
                                        } catch (Exception e) {
                                            toolArgs = toolCall.arguments();
                                        }

                                        pending.put(callId, Map.of("toolName", toolName, "toolArgs", toolArgs));
                                        sseEvents.add(sseData(Map.of(
                                                "type", "tool_call",
                                                "id", callId,
                                                "toolName", toolName,
                                                "toolArgs", toolArgs
                                        )));
                                    }
                                } else {
                                    String answerText = assistantMsg.getText();
                                    textBuffer.setLength(0);
                                    if (answerText != null && !answerText.isEmpty()) {
                                        sseEvents.add(sseData(Map.of("type", "answer", "content", answerText)));
                                    }
                                }
                            }
                        }
                        case AGENT_TOOL_FINISHED -> {
                            var message = streamingOutput.message();
                            if (message instanceof ToolResponseMessage toolResp) {
                                for (var resp : toolResp.getResponses()) {
                                    String resultContent;
                                    try {
                                        Object parsed = objectMapper.readValue(String.valueOf(resp.responseData()), Object.class);
                                        resultContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
                                    } catch (Exception e) {
                                        resultContent = String.valueOf(resp.responseData());
                                    }

                                    String respId = resp.id();
                                    String toolName = "";
                                    Map<String, Map<String, String>> pending = pendingToolCalls.get();
                                    if (respId != null && pending.containsKey(respId)) {
                                        toolName = pending.get(respId).get("toolName");
                                        pending.remove(respId);
                                    } else if (!pending.isEmpty()) {
                                        var iterator = pending.entrySet().iterator();
                                        if (iterator.hasNext()) {
                                            var entry = iterator.next();
                                            toolName = entry.getValue().get("toolName");
                                            iterator.remove();
                                        }
                                    }

                                    sseEvents.add(sseData(Map.of(
                                            "type", "tool_result",
                                            "id", respId != null ? respId : "",
                                            "toolName", toolName,
                                            "content", resultContent
                                    )));
                                }
                            }
                        }
                        default -> {}
                    }

                    return Flux.fromIterable(sseEvents);
                })
                .concatWith(Flux.just(sseData(Map.of("type", "done"))))
                .onErrorResume(error -> {
                    log.error("Agent流式调用失败: {}", error.getMessage(), error);
                    return Flux.just(sseData(Map.of("type", "error", "content", "处理出错，请重试")));
                });
    }

    @Override
    public void deleteChat(String chatId) {
        chatHistoryRepository.delete(ChatType.SERVICE.getType(), chatId);
        // 清理 RedisSaver 的 checkpoint 数据
        try {
            String metaKey = THREAD_META_PREFIX + chatId;
            RMap<String, String> meta = redissonClient.getMap(metaKey);
            String threadId = meta.get("thread_id");
            if (threadId != null) {
                redissonClient.getBucket(CHECKPOINT_PREFIX + threadId).delete();
                redissonClient.getMap(THREAD_REVERSE_PREFIX + threadId).delete();
            }
            meta.delete();
        } catch (Exception e) {
            log.warn("清理RedisSaver checkpoint数据失败, chatId={}: {}", chatId, e.getMessage());
        }
    }

    private String sseData(Map<String, Object> data) {
        try {
            return "data: " + objectMapper.writeValueAsString(data) + "\n\n";
        } catch (Exception e) {
            return "data: {\"type\":\"unknown\"}\n\n";
        }
    }
}
