package com.xdu.aibot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xdu.aibot.mapper.ChatHistoryMapper;
import com.xdu.aibot.pojo.entity.ChatHistory;
import com.xdu.aibot.repository.ChatHistoryRepository;
import com.xdu.aibot.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory>
        implements ChatHistoryService, ChatHistoryRepository {

    @Override
    public void saveChat(String type, String chatId) {
        log.info("保存会话: {}", chatId);
        if (!StringUtils.hasText(chatId)){
            return;
        }
        // 检查是否已存在
        boolean exists = this.exists(new LambdaQueryWrapper<ChatHistory>()
                .eq(ChatHistory::getType, type)
                .eq(ChatHistory::getChatId, chatId));

        if (!exists) {
            ChatHistory history = new ChatHistory();
            history.setType(type);
            history.setChatId(chatId);
            history.setCreatedAt(LocalDateTime.now());
            this.save(history); // 调用父类 save()
        }
    }

    @Override
    public List<String> getChatIdsByType(String type) {
        log.info("查询对话类型: {}", type);
        return this.list(new LambdaQueryWrapper<ChatHistory>()
                        .select(ChatHistory::getChatId) // 只查 chat_id 字段
                        .eq(ChatHistory::getType, type))
                .stream()
                .map(ChatHistory::getChatId)
                .collect(Collectors.toList());
    }

    @Override
    public void save(String type, String chatId) {
        saveChat(type, chatId);
    }

    @Override
    public List<String> getChatIds(String type) {
        return getChatIdsByType(type);
    }

    @Override
    public void delete(String type, String chatId) {
        this.remove(new LambdaQueryWrapper<ChatHistory>()
                .eq(ChatHistory::getType, type)
                .eq(ChatHistory::getChatId, chatId));
    }
}