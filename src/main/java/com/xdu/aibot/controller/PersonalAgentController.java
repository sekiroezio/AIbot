package com.xdu.aibot.controller;

import com.xdu.aibot.service.PersonalAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ai")
public class PersonalAgentController {

    private final PersonalAgent personalAgent;

    public PersonalAgentController(PersonalAgent personalAgent) {
        this.personalAgent = personalAgent;
    }

    @RequestMapping(value = "/service", produces = "text/html;charset=utf-8")
    public String chat(String prompt, String chatId) {
        return personalAgent.chat(prompt, chatId);
    }

    @GetMapping(value = "/service/stream", produces = "text/html;charset=utf-8")
    public Flux<String> chatStream(String prompt, String chatId) throws GraphRunnerException {
        return personalAgent.chatStream(prompt, chatId);
    }

    @DeleteMapping("/service/chat/{chatId}")
    public Map<String, Object> deleteChat(@PathVariable("chatId") String chatId) {
        personalAgent.deleteChat(chatId);
        return Map.of("success", true);
    }
}
