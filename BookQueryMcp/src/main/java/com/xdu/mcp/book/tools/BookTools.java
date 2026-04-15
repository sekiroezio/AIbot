package com.xdu.mcp.book.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xdu.mcp.book.entity.Book;
import com.xdu.mcp.book.entity.BookReservation;
import com.xdu.mcp.book.service.IBookReservationService;
import com.xdu.mcp.book.service.IBookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookTools {

    private final IBookService bookService;
    private final IBookReservationService bookReservationService;
    private final ObjectMapper objectMapper;

    @McpTool(name = "查询书籍信息",description = "按类型、作者、书名、最低评分、最低库存查询书籍")
    public String queryBook(
            @ToolParam(description = "书籍类型(只能是其中之一)：亚洲文学、欧美文学、诗歌、科幻、历史、其它", required = false) String type,
            @ToolParam(description = "作者姓名", required = false) String author,
            @ToolParam(description = "书籍名称", required = false) String name,
            @ToolParam(description = "最低评分（1-10）", required = false) Integer minScore,
            @ToolParam(description = "最低库存", required = false) Integer minStock
    ) {
        LambdaQueryWrapper<Book> queryWrapper = new LambdaQueryWrapper<Book>()
                .eq(type != null, Book::getType, type)
                .eq(author != null, Book::getAuthor, author)
                .like(name != null, Book::getName, name)
                .ge(minScore != null, Book::getScore, minScore)
                .gt(minStock != null, Book::getStock, minStock)
                .orderByDesc(Book::getScore);

        List<Book> books = bookService.list(queryWrapper);

        if (books.isEmpty()) {
            return errorJson("未找到匹配书籍");
        }

        return toJson(books);
    }

    @McpTool(name = "预约图书",description = "仅当书籍有库存时才能预约成功，预约成功后自动扣减库存。返回预约单号。")
    @Transactional
    public String reserveBook(
            @ToolParam(description = "书籍名称") String bookName,
            @ToolParam(description = "借阅人姓名") String readerName,
            @ToolParam(description = "借阅人电话") String phone,
            @ToolParam(description = "备注（如特殊取书时间要求，可选）", required = false) String remark
    ) {
        Book book = bookService.getOne(
                new LambdaQueryWrapper<Book>().eq(Book::getName, bookName)
        );

        if (book == null) {
            return errorJson("未找到书名为《" + bookName + "》的书籍，请确认书名是否正确");
        }

        if (book.getStock() <= 0) {
            return errorJson("《" + bookName + "》当前库存不足，无法预约");
        }

        book.setStock(book.getStock() - 1);
        boolean updated = bookService.updateById(book);
        if (!updated) {
            return errorJson("库存更新失败，请重试");
        }

        BookReservation reservation = new BookReservation();
        reservation.setBookName(bookName);
        reservation.setReaderName(readerName);
        reservation.setPhone(phone);
        reservation.setRemark(remark);
        bookReservationService.save(reservation);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        result.put("reservationId", reservation.getId());
        result.put("bookName", bookName);
        result.put("readerName", readerName);
        result.put("message", "预约成功！");

        log.info("图书预约成功: 书名={}, 读者={}, 预约单号={}", bookName, readerName, reservation.getId());
        return toJson(result);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"JSON序列化失败\"}";
        }
    }

    private String errorJson(String message) {
        return "{\"status\":\"ERROR\",\"message\":\"" + message + "\"}";
    }
}
