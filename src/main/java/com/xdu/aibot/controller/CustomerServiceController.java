package com.xdu.aibot.controller;

import com.xdu.aibot.service.CustomerService;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ai")
public class CustomerServiceController {

    private final CustomerService customerService;

    public CustomerServiceController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @RequestMapping(value = "/service", produces = "text/html;charset=utf-8")
    public String chat(String prompt, String chatId) {
        return customerService.chat(prompt, chatId);
    }

    @GetMapping(value = "/service/stream", produces = "text/html;charset=utf-8")
    public Flux<String> chatStream(String prompt, String chatId) throws GraphRunnerException {
        return customerService.chatStream(prompt, chatId);
    }

    @DeleteMapping("/service/chat/{chatId}")
    public Map<String, Object> deleteChat(@PathVariable("chatId") String chatId) {
        customerService.deleteChat(chatId);
        return Map.of("success", true);
    }
}
