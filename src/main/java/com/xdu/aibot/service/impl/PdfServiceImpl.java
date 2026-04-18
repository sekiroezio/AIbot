package com.xdu.aibot.service.impl;

import com.xdu.aibot.constant.ChatType;
import com.xdu.aibot.pojo.vo.Result;
import com.xdu.aibot.repository.ChatHistoryRepository;
import com.xdu.aibot.repository.FileRepository;
import com.xdu.aibot.service.PdfService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class PdfServiceImpl implements PdfService {

    private final FileRepository fileService;
    private final ChatClient pdfChatClient;
    private final ObjectMapper objectMapper;
    private final ChatHistoryRepository chatHistoryRepository;
    private final ChatMemory chatMemory;

    public PdfServiceImpl(@Qualifier("graphPdfFileRepository") FileRepository fileService,
                          ChatClient pdfChatClient,
                          ObjectMapper objectMapper,
                          @Qualifier("chatHistoryServiceImpl") ChatHistoryRepository chatHistoryRepository,
                          ChatMemory chatMemory) {
        this.fileService = fileService;
        this.pdfChatClient = pdfChatClient;
        this.objectMapper = objectMapper;
        this.chatHistoryRepository = chatHistoryRepository;
        this.chatMemory = chatMemory;
    }

    @Override
    public String chat(String prompt, String chatId) {
        Resource file = fileService.getFile(chatId);
        if (!file.exists()) {
            throw new RuntimeException("请先上传文件！");
        }
        chatHistoryRepository.save(ChatType.PDF.getType(), chatId);
        return pdfChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "file_name == '" + file.getFilename() + "'"))
                .call()
                .content();
    }

    @Override
    public Flux<String> chatStream(String prompt, String chatId) {
        Resource file = fileService.getFile(chatId);
        if (!file.exists()) {
            return Flux.just(sseData(Map.of("type", "error", "content", "请先上传文件！")));
        }
        chatHistoryRepository.save(ChatType.PDF.getType(), chatId);

        return pdfChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "file_name == '" + file.getFilename() + "'"))
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

    @Override
    public Result uploadPdf(String chatId, MultipartFile file) {
        try {
            if (!Objects.equals(file.getContentType(), "application/pdf")) {
                return Result.fail("只能上传PDF文件！");
            }
            boolean success = fileService.save(chatId, file.getResource());
            if (!success) {
                return Result.fail("保存文件失败！");
            }
            return Result.ok();
        } catch (Exception e) {
            log.error("Failed to upload PDF.", e);
            return Result.fail("上传文件失败！");
        }
    }

    @Override
    public void deleteChat(String chatId) {
        chatHistoryRepository.delete(ChatType.PDF.getType(), chatId);
        chatMemory.clear(chatId);
    }

    @Override
    public ResponseEntity<Resource> downloadFile(String chatId) {
        Resource resource = fileService.getFile(chatId);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        String filename = URLEncoder.encode(Objects.requireNonNull(resource.getFilename()), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(resource);
    }

    private String sseData(Map<String, Object> data) {
        try {
            return "data: " + objectMapper.writeValueAsString(data) + "\n\n";
        } catch (Exception e) {
            return "data: {\"type\":\"unknown\"}\n\n";
        }
    }
}
