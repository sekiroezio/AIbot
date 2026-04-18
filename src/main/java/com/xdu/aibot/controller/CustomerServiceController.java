package com.xdu.aibot.controller;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xdu.aibot.constant.ChatType;
import com.xdu.aibot.repository.ChatHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * 流式接口：使用状态机缓冲文本，确保：
     * 1. 连续的纯文本token合并后再输出
     * 2. 工具调用前的文本 -> thinking
     * 3. tool_call 紧跟 tool_result 成对输出
     * 4. 最后的文本 -> answer（最终回答）
     */
    @RequestMapping(value = "/service/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(String prompt, String chatId) {
        chatHistoryRepository.save(ChatType.SERVICE.getType(), chatId);

        SseEmitter emitter = new SseEmitter(300000L);

        new Thread(() -> {
            // 文本缓冲区：收集连续的纯文本token
            StringBuilder textBuffer = new StringBuilder();
            // 标记是否已经输出过工具调用（用于区分中间文本是thinking还是最后的answer）
            AtomicBoolean hasToolCall = new AtomicBoolean(false);

            try {
                Flux<Message> messageFlux = bookAgent.streamMessages(prompt);
                messageFlux.subscribe(
                        message -> {
                            try {
                                if (message instanceof AssistantMessage assistantMsg) {
                                    if (assistantMsg.hasToolCalls()) {
                                        // --- 遇到工具调用 ---

                                        // 1. 先把缓冲的文本作为"思考"输出
                                        flushText(emitter, textBuffer, "thinking");

                                        // 2. 输出工具调用
                                        hasToolCall.set(true);
                                        for (var toolCall : assistantMsg.getToolCalls()) {
                                            Map<String, Object> event = new HashMap<>();
                                            event.put("type", "tool_call");
                                            event.put("toolName", toolCall.name());
                                            try {
                                                Object parsed = objectMapper.readValue(toolCall.arguments(), Object.class);
                                                event.put("toolArgs", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
                                            } catch (Exception e) {
                                                event.put("toolArgs", toolCall.arguments());
                                            }
                                            sendEvent(emitter, event);
                                        }
                                    } else {
                                        // --- 纯文本token：缓冲起来 ---
                                        String text = assistantMsg.getText();
                                        if (text != null) {
                                            textBuffer.append(text);
                                        }
                                    }
                                } else if (message instanceof ToolResponseMessage toolResp) {
                                    // --- 工具结果：紧跟工具调用输出 ---
                                    for (var resp : toolResp.getResponses()) {
                                        Map<String, Object> event = new HashMap<>();
                                        event.put("type", "tool_result");
                                        event.put("toolName", resp.name());
                                        try {
                                            Object parsed = objectMapper.readValue(String.valueOf(resp.responseData()), Object.class);
                                            event.put("content", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
                                        } catch (Exception e) {
                                            event.put("content", String.valueOf(resp.responseData()));
                                        }
                                        sendEvent(emitter, event);
                                    }
                                }
                                // 忽略 UserMessage 等其他类型
                            } catch (Exception e) {
                                log.error("处理消息失败: {}", e.getMessage(), e);
                            }
                        },
                        error -> {
                            log.error("Agent流式调用失败: {}", error.getMessage(), error);
                            try {
                                // 刷新剩余文本
                                flushText(emitter, textBuffer, "answer");
                                Map<String, Object> errEvent = Map.of("type", "error", "content", "处理出错，请重试");
                                sendEvent(emitter, errEvent);
                            } catch (Exception ignored) {}
                            emitter.completeWithError(error);
                        },
                        () -> {
                            try {
                                // 流结束：把剩余文本作为最终回答输出
                                if (textBuffer.length() > 0) {
                                    if (hasToolCall.get()) {
                                        // 有过工具调用，最后的文本是最终回答
                                        flushText(emitter, textBuffer, "answer");
                                    } else {
                                        // 没有工具调用，整段都是回答
                                        flushText(emitter, textBuffer, "answer");
                                    }
                                }
                                sendEvent(emitter, Map.of("type", "done"));
                            } catch (Exception e) {
                                log.error("完成事件发送失败: {}", e.getMessage(), e);
                            }
                            emitter.complete();
                        }
                );
            } catch (Exception e) {
                log.error("Agent流式调用异常: {}", e.getMessage(), e);
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    /**
     * 将缓冲区文本作为指定类型的事件输出，然后清空缓冲区
     */
    private void flushText(SseEmitter emitter, StringBuilder textBuffer, String type) throws IOException {
        if (textBuffer.length() == 0) return;
        String text = textBuffer.toString().trim();
        textBuffer.setLength(0);
        if (text.isEmpty()) return;

        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("content", text);
        sendEvent(emitter, event);
    }

    private void sendEvent(SseEmitter emitter, Map<String, Object> event) throws IOException {
        String json = objectMapper.writeValueAsString(event);
        emitter.send(SseEmitter.event().data(json));
    }

    @DeleteMapping("/service/chat/{chatId}")
    public Map<String, Object> deleteChat(@PathVariable("chatId") String chatId) {
        chatHistoryRepository.delete(ChatType.SERVICE.getType(), chatId);
        return Map.of("success", true);
    }
}
