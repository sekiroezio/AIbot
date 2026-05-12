package com.xdu.aibot.service;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import reactor.core.publisher.Flux;

public interface PersonalAgent {

    /**
     * 客服同步问答
     */
    String chat(String prompt, String chatId);

    /**
     * 客服流式问答
     */
    Flux<String> chatStream(String prompt, String chatId) throws GraphRunnerException;

    /**
     * 删除会话
     */
    void deleteChat(String chatId);
}
