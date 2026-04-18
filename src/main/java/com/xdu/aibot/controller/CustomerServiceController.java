package com.xdu.aibot.controller;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xdu.aibot.constant.ChatType;
import com.xdu.aibot.repository.ChatHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 跟踪当前ReAct阶段，用于区分思考(Reason)和观察(Observe)
     */
    private static final ThreadLocal<String> reactPhase = ThreadLocal.withInitial(() -> "reason");

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

    @RequestMapping(value = "/service/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(String prompt, String chatId) {
        chatHistoryRepository.save(ChatType.SERVICE.getType(), chatId);

        SseEmitter emitter = new SseEmitter(300000L);

        new Thread(() -> {
            try {
                reactPhase.set("reason");
                Flux<Message> messageFlux = bookAgent.streamMessages(prompt);
                messageFlux.subscribe(
                        message -> {
                            try {
                                List<Map<String, Object>> events = buildEvents(message);
                                for (Map<String, Object> event : events) {
                                    String json = objectMapper.writeValueAsString(event);
                                    emitter.send(SseEmitter.event().data(json));
                                }
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            log.error("Agent流式调用失败: {}", error.getMessage(), error);
                            try {
                                Map<String, Object> errEvent = Map.of("type", "error", "content", "处理出错，请重试");
                                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(errEvent)));
                            } catch (Exception ignored) {}
                            emitter.completeWithError(error);
                        },
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(Map.of("type", "done"))));
                            } catch (Exception ignored) {}
                            emitter.complete();
                        }
                );
            } catch (Exception e) {
                log.error("Agent流式调用异常: {}", e.getMessage(), e);
                emitter.completeWithError(e);
            } finally {
                reactPhase.remove();
            }
        }).start();

        return emitter;
    }

    /**
     * 构建SSE事件列表，区分ReAct的思考、观察、工具调用和最终回答
     */
    private List<Map<String, Object>> buildEvents(Message message) {
        if (message instanceof AssistantMessage assistantMsg) {
            if (assistantMsg.hasToolCalls()) {
                // Agent决定调用工具 -> 这是"行动(Act)"阶段
                reactPhase.set("observe"); // 下一个AssistantMessage应该是观察
                return assistantMsg.getToolCalls().stream().map(toolCall -> {
                    Map<String, Object> event = new HashMap<>();
                    event.put("type", "tool_call");
                    event.put("toolName", toolCall.name());
                    try {
                        // 尝试格式化参数为可读JSON
                        Object parsed = objectMapper.readValue(toolCall.arguments(), Object.class);
                        event.put("toolArgs", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
                    } catch (Exception e) {
                        event.put("toolArgs", toolCall.arguments());
                    }
                    return event;
                }).toList();
            } else {
                String text = assistantMsg.getText();
                if (text != null && !text.isEmpty()) {
                    String phase = reactPhase.get();
                    if ("observe".equals(phase)) {
                        // 工具调用后、下一次工具调用前的文本 -> 观察阶段
                        reactPhase.set("reason");
                        Map<String, Object> event = new HashMap<>();
                        event.put("type", "observation");
                        event.put("content", text);
                        return List.of(event);
                    } else {
                        // 最终回答
                        Map<String, Object> event = new HashMap<>();
                        event.put("type", "answer");
                        event.put("content", text);
                        return List.of(event);
                    }
                } else {
                    // 空文本的AssistantMessage -> 思考阶段
                    Map<String, Object> event = new HashMap<>();
                    event.put("type", "thinking");
                    event.put("content", "正在分析问题...");
                    return List.of(event);
                }
            }
        } else if (message instanceof ToolResponseMessage toolResp) {
            return toolResp.getResponses().stream().map(resp -> {
                Map<String, Object> event = new HashMap<>();
                event.put("type", "tool_result");
                event.put("toolName", resp.name());
                try {
                    Object parsed = objectMapper.readValue(String.valueOf(resp.responseData()), Object.class);
                    event.put("content", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
                } catch (Exception e) {
                    event.put("content", String.valueOf(resp.responseData()));
                }
                return event;
            }).toList();
        } else if (message instanceof UserMessage) {
            // 忽略用户消息的回显
            return List.of();
        } else {
            // 其他类型消息作为思考处理
            Map<String, Object> event = new HashMap<>();
            event.put("type", "thinking");
            String text = message.getText();
            event.put("content", text != null && !text.isEmpty() ? text : "正在推理...");
            return List.of(event);
        }
    }

    @DeleteMapping("/service/chat/{chatId}")
    public Map<String, Object> deleteChat(@PathVariable("chatId") String chatId) {
        chatHistoryRepository.delete(ChatType.SERVICE.getType(), chatId);
        return Map.of("success", true);
    }
}
