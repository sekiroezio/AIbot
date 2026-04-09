package com.xdu.aibot.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xdu.aibot.pojo.entity.ChatHistory;

import java.util.List;

public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 保存会话（自动去重）
     */
    void saveChat(String type, String chatId);

    /**
     * 根据类型获取所有 chatId 列表
     */
    List<String> getChatIdsByType(String type);
}