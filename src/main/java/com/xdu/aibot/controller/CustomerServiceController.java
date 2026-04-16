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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.HashMap;
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
                Flux<Message> messageFlux = bookAgent.streamMessages(prompt);
                messageFlux.subscribe(
                        message -> {
                            try {
                                Map<String, Object> event = buildEvent(message);
                                String json = objectMapper.writeValueAsString(event);
                                emitter.send(SseEmitter.event().data(json));
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
            }
        }).start();

        return emitter;
    }

    private Map<String, Object> buildEvent(Message message) {
        Map<String, Object> event = new HashMap<>();

        if (message instanceof AssistantMessage assistantMsg) {
            if (assistantMsg.hasToolCalls()) {
                event.put("type", "tool_call");
                var toolCall = assistantMsg.getToolCalls().get(0);
                event.put("toolName", toolCall.name());
                event.put("toolArgs", toolCall.arguments());
                event.put("content", "调用工具: " + toolCall.name());
            } else {
                String text = assistantMsg.getText();
                if (text != null && !text.isEmpty()) {
                    event.put("type", "answer");
                    event.put("content", text);
                } else {
                    event.put("type", "thinking");
                    event.put("content", "思考中...");
                }
            }
        } else if (message instanceof ToolResponseMessage toolResp) {
            event.put("type", "tool_result");
            var responses = toolResp.getResponses();
            if (!responses.isEmpty()) {
                var resp = responses.get(0);
                event.put("toolName", resp.name());
                event.put("content", resp.responseData());
            }
        } else {
            event.put("type", "thinking");
            String text = message.getText();
            event.put("content", text != null ? text : "");
        }

        return event;
    }
}
