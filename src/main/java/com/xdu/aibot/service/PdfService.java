package com.xdu.aibot.service;

import com.xdu.aibot.pojo.vo.Result;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

public interface PdfService {

    /**
     * PDF同步问答
     */
    String chat(String prompt, String chatId);

    /**
     * PDF流式问答
     */
    Flux<String> chatStream(String prompt, String chatId);

    /**
     * 上传PDF文件
     */
    Result uploadPdf(String chatId, MultipartFile file);

    /**
     * 删除会话
     */
    void deleteChat(String chatId);

    /**
     * 下载PDF文件
     */
    ResponseEntity<Resource> downloadFile(String chatId);
}
