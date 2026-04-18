package com.xdu.aibot.controller;

import com.xdu.aibot.pojo.vo.Result;
import com.xdu.aibot.service.PdfService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ai/pdf")
public class PdfController {

    private final PdfService pdfService;

    public PdfController(PdfService pdfService) {
        this.pdfService = pdfService;
    }

    @RequestMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public String chat(String prompt, String chatId) {
        return pdfService.chat(prompt, chatId);
    }

    @GetMapping(value = "/chat/stream", produces = "text/html;charset=utf-8")
    public Flux<String> chatStream(String prompt, String chatId) {
        return pdfService.chatStream(prompt, chatId);
    }

    @RequestMapping("/upload/{chatId}")
    public Result uploadPdf(@PathVariable String chatId, @RequestParam("file") MultipartFile file) {
        return pdfService.uploadPdf(chatId, file);
    }

    @DeleteMapping("/chat/{chatId}")
    public Map<String, Object> deleteChat(@PathVariable("chatId") String chatId) {
        pdfService.deleteChat(chatId);
        return Map.of("success", true);
    }

    @GetMapping("/file/{chatId}")
    public ResponseEntity<Resource> download(@PathVariable("chatId") String chatId) throws IOException {
        return pdfService.downloadFile(chatId);
    }
}
