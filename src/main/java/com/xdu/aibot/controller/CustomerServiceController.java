package com.xdu.aibot.controller;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xdu.aibot.constant.ChatType;
import com.xdu.aibot.repository.ChatHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequestMapping("/ai")
public class CustomerServiceController {

    @Autowired
    private ReactAgent bookAgent;

    @Autowired
    private ObjectMapper objectMapper;

    @Qualifier("chatHistoryServiceImpl")
    @Autowired
    private ChatHistoryRepository chatHistoryRepository;

    @RequestMapping(value = "/service", produces = "text/html;charset=utf-8")
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

    /**
     * 流式接口：直接返回 Flux<ServerSentEvent<String>>
     * 利用 Spring 对响应式返回类型的支持，自动实现 SSE 流式输出。
     *
     * 事件类型：
     * - token:       LLM 逐 token 流式输出（实时显示用）
     * - thinking:    LLM 调用工具前的思考文本
     * - tool_call:   工具调用（参数）
     * - tool_result: 工具调用结果（紧跟对应的 tool_call 后面）
     * - answer:      最终回答（Markdown 渲染）
     * - done:        流结束
     */
    @GetMapping(value = "/service/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(String prompt, String chatId) throws GraphRunnerException {
        chatHistoryRepository.save(ChatType.SERVICE.getType(), chatId);

        // 缓冲区：收集 AGENT_MODEL_STREAMING 的 token
        StringBuilder textBuffer = new StringBuilder();
        // 缓冲：AGENT_MODEL_FINISHED 中的 toolCalls，等 AGENT_TOOL_FINISHED 配对后一起发出
        // key = toolCall.id(), value = { toolName, args }
        AtomicReference<Map<String, Map<String, String>>> pendingToolCalls = new AtomicReference<>(new LinkedHashMap<>());

        return bookAgent.stream(prompt, RunnableConfig.builder().threadId(chatId).build())
                .flatMap(nodeOutput -> {
                    if (!(nodeOutput instanceof StreamingOutput<?> streamingOutput)) {
                        return Flux.empty();
                    }

                    OutputType outputType = streamingOutput.getOutputType();
                    if (outputType == null) return Flux.empty();

                    List<ServerSentEvent<String>> events = new ArrayList<>();

                    switch (outputType) {
                        case AGENT_MODEL_STREAMING -> {
                            // 逐 token 流式输出
                            String chunk = streamingOutput.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                textBuffer.append(chunk);
                                events.add(buildEvent("token", Map.of("type", "token", "content", chunk)));
                            }
                        }
                        case AGENT_MODEL_FINISHED -> {
                            var message = streamingOutput.message();
                            if (message instanceof AssistantMessage assistantMsg) {
                                if (assistantMsg.hasToolCalls()) {
                                    // LLM 决定调用工具
                                    // 1. 缓冲的文本作为"思考"输出
                                    String thinkingText = textBuffer.toString().trim();
                                    textBuffer.setLength(0);
                                    if (!thinkingText.isEmpty()) {
                                        events.add(buildEvent("thinking", Map.of("type", "thinking", "content", thinkingText)));
                                    }

                                    // 2. 发出 tool_call，并缓存到 pendingToolCalls 等待配对
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
                                        events.add(buildEvent("tool_call", Map.of(
                                                "type", "tool_call",
                                                "id", callId,
                                                "toolName", toolName,
                                                "toolArgs", toolArgs
                                        )));
                                    }
                                } else {
                                    // LLM 最终文本回答
                                    // 文本已通过 token 事件实时发送，直接发 answer
                                    String answerText = assistantMsg.getText();
                                    textBuffer.setLength(0);
                                    if (answerText != null && !answerText.isEmpty()) {
                                        events.add(buildEvent("answer", Map.of("type", "answer", "content", answerText)));
                                    }
                                }
                            }
                        }
                        case AGENT_TOOL_FINISHED -> {
                            // 工具执行完成 — 配对输出结果
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

                                    // 尝试通过 id 配对获取 toolName
                                    String respId = resp.id();
                                    String toolName = "";
                                    Map<String, Map<String, String>> pending = pendingToolCalls.get();
                                    if (respId != null && pending.containsKey(respId)) {
                                        toolName = pending.get(respId).get("toolName");
                                        pending.remove(respId);
                                    } else if (!pending.isEmpty()) {
                                        // fallback: 按顺序取第一个未配对的
                                        var iterator = pending.entrySet().iterator();
                                        if (iterator.hasNext()) {
                                            var entry = iterator.next();
                                            toolName = entry.getValue().get("toolName");
                                            iterator.remove();
                                        }
                                    }

                                    events.add(buildEvent("tool_result", Map.of(
                                            "type", "tool_result",
                                            "id", respId != null ? respId : "",
                                            "toolName", toolName,
                                            "content", resultContent
                                    )));
                                }
                            }
                        }
                        default -> {
                            // AGENT_TOOL_STREAMING 等忽略
                        }
                    }

                    return Flux.fromIterable(events);
                })
                .concatWith(Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("{\"type\":\"done\"}")
                                .build()
                ))
                .onErrorResume(error -> {
                    log.error("Agent流式调用失败: {}", error.getMessage(), error);
                    return Flux.just(
                            ServerSentEvent.<String>builder()
                                    .event("error")
                                    .data("{\"type\":\"error\",\"content\":\"处理出错，请重试\"}")
                                    .build()
                    );
                });
    }

    private ServerSentEvent<String> buildEvent(String eventType, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return ServerSentEvent.<String>builder()
                    .event(eventType)
                    .data(json)
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder()
                    .event(eventType)
                    .data("{\"type\":\"" + eventType + "\"}")
                    .build();
        }
    }

    @DeleteMapping("/service/chat/{chatId}")
    public Map<String, Object> deleteChat(@PathVariable("chatId") String chatId) {
        chatHistoryRepository.delete(ChatType.SERVICE.getType(), chatId);
        return Map.of("success", true);
    }
}
