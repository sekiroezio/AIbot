package com.xdu.aibot.service;

import java.util.Map;

public interface GraphService {

    /**
     * 获取指定会话的知识图谱数据
     */
    Map<String, Object> getGraphData(String chatId);
}
