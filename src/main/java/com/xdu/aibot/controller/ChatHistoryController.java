package com.xdu.aibot.controller;

import com.xdu.aibot.pojo.vo.MessageVO;
import com.xdu.aibot.repository.ChatHistoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/ai/history")
public class ChatHistoryController {

    private final ChatHistoryRepository chatHistoryRepository;
    private final ChatMemory chatMemory;

    public ChatHistoryController(@Qualifier("chatHistoryServiceImpl") ChatHistoryRepository chatHistoryRepository,
                                  ChatMemory chatMemory) {
        this.chatHistoryRepository = chatHistoryRepository;
        this.chatMemory = chatMemory;
    }

    @GetMapping("/{type}")
    public List<String> getChatIds(@PathVariable("type") String type) {
        return chatHistoryRepository.getChatIds(type);
    }

    @GetMapping("/{type}/{chatId}")
    public List<MessageVO> getChatHistory(@PathVariable("type") String type, @PathVariable("chatId") String chatId) {
        List<Message> messages = chatMemory.get(chatId);
        if (messages == null) {
            return List.of();
        }
        return messages.stream().map(MessageVO::new).toList();
    }
}
