package com.xdu.aibot.controller;

import com.xdu.aibot.constant.ChatType;
import com.xdu.aibot.pojo.vo.Result;
import com.xdu.aibot.repository.ChatHistoryRepository;
import com.xdu.aibot.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/pdf")
public class PdfController {

    @Autowired
    @Qualifier("graphPdfFileRepository")
    private FileRepository fileService;

    private final ChatClient pdfChatClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Qualifier("chatHistoryServiceImpl")
    @Autowired
    private ChatHistoryRepository chatHistoryRepository;

    @RequestMapping(value = "/chat",produces = "text/html;charset=utf-8")
    public String chat(String prompt, String chatId) {
        Resource file = fileService.getFile(chatId);
        if (!file.exists()){
            throw new RuntimeException("请先上传文件！");
        }
        chatHistoryRepository.save(ChatType.PDF.getType(), chatId);
        return pdfChatClient.prompt()
                .user(prompt)
                .advisors(a->a.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(a->a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "file_name == '"+file.getFilename()+"'"))
                .call()
                .content();
    }

    /**
     * 流式接口 — 使用 ChatClient.stream() + Flux<String>
     * produces = "text/html;charset=utf-8" 保证 Servlet 容器下逐元素 flush
     * 手动拼接 SSE 格式，前端用 fetch+ReadableStream 解析
     *
     * 事件类型：token（流式文本）/ answer（完整回答）/ done（结束）
     */
    @GetMapping(value = "/chat/stream", produces = "text/html;charset=utf-8")
    public Flux<String> chatStream(String prompt, String chatId) {
        Resource file = fileService.getFile(chatId);
        if (!file.exists()){
            return Flux.just(sseData(Map.of("type", "error", "content", "请先上传文件！")));
        }
        chatHistoryRepository.save(ChatType.PDF.getType(), chatId);

        return pdfChatClient.prompt()
                .user(prompt)
                .advisors(a->a.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(a->a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "file_name == '"+file.getFilename()+"'"))
                .stream()
                .content()
                .map(chunk -> {
                    if (chunk != null && !chunk.isEmpty()) {
                        return sseData(Map.of("type", "token", "content", chunk));
                    }
                    return "";
                })
                .filter(s -> !s.isEmpty())
                .concatWith(Flux.just(sseData(Map.of("type", "done"))));
    }

    private String sseData(Map<String, Object> data) {
        try {
            return "data: " + objectMapper.writeValueAsString(data) + "\n\n";
        } catch (Exception e) {
            return "data: {\"type\":\"unknown\"}\n\n";
        }
    }

    /**
     * 文件上传
     */
    @RequestMapping("/upload/{chatId}")
    public Result uploadPdf(@PathVariable String chatId, @RequestParam("file") MultipartFile file) {
        try {
            // 1. 校验文件是否为PDF格式
            if (!Objects.equals(file.getContentType(), "application/pdf")) {
                return Result.fail("只能上传PDF文件！");
            }
            // 2.保存文件
            boolean success = fileService.save(chatId, file.getResource());
            if(! success) {
                return Result.fail("保存文件失败！");
            }
            return Result.ok();
        } catch (Exception e) {
            log.error("Failed to upload PDF.", e);
            return Result.fail("上传文件失败！");
        }
    }

    /**
     * 文件下载
     */
    @GetMapping("/file/{chatId}")
    public ResponseEntity<Resource> download(@PathVariable("chatId") String chatId) throws IOException {
        // 1.读取文件
        Resource resource = fileService.getFile(chatId);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        // 2.文件名编码，写入响应头
        String filename = URLEncoder.encode(Objects.requireNonNull(resource.getFilename()), StandardCharsets.UTF_8);
        // 3.返回文件
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(resource);
    }

}