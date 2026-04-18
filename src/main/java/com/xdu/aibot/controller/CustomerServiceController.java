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
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import jakarta.servlet.http.HttpServletResponse;
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
     * 流式接口：返回 Flux<String>，每个元素是纯 JSON 字符串
     * Spring MVC 会自动将每个 String 包装为 SSE 格式: data: {json}\n\n
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
    public Flux<String> chatStream(String prompt, String chatId, HttpServletResponse response) throws GraphRunnerException {
        chatHistoryRepository.save(ChatType.SERVICE.getType(), chatId);

        // 禁用代理和浏览器缓冲，确保 SSE 实时推送
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        StringBuilder textBuffer = new StringBuilder();
        AtomicReference<Map<String, Map<String, String>>> pendingToolCalls = new AtomicReference<>(new LinkedHashMap<>());

        return bookAgent.stream(prompt, RunnableConfig.builder().threadId(chatId).build())
                .flatMap(nodeOutput -> {
                    if (!(nodeOutput instanceof StreamingOutput<?> streamingOutput)) {
                        return Flux.empty();
                    }

                    OutputType outputType = streamingOutput.getOutputType();
                    if (outputType == null) return Flux.empty();

                    List<String> jsonEvents = new ArrayList<>();

                    switch (outputType) {
                        case AGENT_MODEL_STREAMING -> {
                            String chunk = streamingOutput.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                textBuffer.append(chunk);
                                jsonEvents.add(toJson(Map.of("type", "token", "content", chunk)));
                            }
                        }
                        case AGENT_MODEL_FINISHED -> {
                            var message = streamingOutput.message();
                            if (message instanceof AssistantMessage assistantMsg) {
                                if (assistantMsg.hasToolCalls()) {
                                    String thinkingText = textBuffer.toString().trim();
                                    textBuffer.setLength(0);
                                    if (!thinkingText.isEmpty()) {
                                        jsonEvents.add(toJson(Map.of("type", "thinking", "content", thinkingText)));
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
                                        jsonEvents.add(toJson(Map.of(
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
                                        jsonEvents.add(toJson(Map.of("type", "answer", "content", answerText)));
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

                                    jsonEvents.add(toJson(Map.of(
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

                    return Flux.fromIterable(jsonEvents);
                })
                .concatWith(Flux.just(toJson(Map.of("type", "done"))))
                .onErrorResume(error -> {
                    log.error("Agent流式调用失败: {}", error.getMessage(), error);
                    return Flux.just(toJson(Map.of("type", "error", "content", "处理出错，请重试")));
                });
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{\"type\":\"unknown\"}";
        }
    }

    @DeleteMapping("/service/chat/{chatId}")
    public Map<String, Object> deleteChat(@PathVariable("chatId") String chatId) {
        chatHistoryRepository.delete(ChatType.SERVICE.getType(), chatId);
        return Map.of("success", true);
    }
}
