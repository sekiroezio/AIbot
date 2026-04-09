package com.xdu.aibot.repository;

import org.springframework.stereotype.Service;

import java.util.List;

public interface ChatHistoryRepository {
    // type业务类型
    void save(String type, String chatId);

    //获取会话id列表
    List<String> getChatIds(String type);
}
