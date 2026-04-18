package com.xdu.aibot.repository.Impl;


import com.xdu.aibot.repository.ChatHistoryRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class InMemoryChatHistoryRepository implements ChatHistoryRepository {

    private final Map<String, List<String>> chatHistory = new HashMap<>();

    @Override
    public void save(String type, String chatId) {
        List<String> chatIds = chatHistory.computeIfAbsent(type, k -> new ArrayList<>());
        if (chatIds.contains(chatId)){
            return;
        }
        chatIds.add(chatId);
    }

    @Override
    public List<String> getChatIds(String type) {
        return chatHistory.getOrDefault(type, new ArrayList<>());
    }

    @Override
    public void delete(String type, String chatId) {
        List<String> chatIds = chatHistory.get(type);
        if (chatIds != null) {
            chatIds.remove(chatId);
        }
    }
}
