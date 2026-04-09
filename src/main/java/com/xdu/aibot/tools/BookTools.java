package com.xdu.aibot.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xdu.aibot.pojo.entity.Book;
import com.xdu.aibot.pojo.entity.BookReservation;
import com.xdu.aibot.pojo.query.BookQuery;
import com.xdu.aibot.service.IBookReservationService;
import com.xdu.aibot.service.IBookService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class BookTools {

    @Autowired
    private IBookService bookService;
    @Autowired
    private IBookReservationService bookReservationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "查询书籍信息。注意：如果查询到的书籍库存为0或不存在，该工具会自动返回推荐书籍列表。")
    public String queryBook(@ToolParam(description = "书籍查询条件", required = false) BookQuery query) {
        // 1. 执行正常的查询逻辑
        List<Book> books;
        if (query == null) {
            books = bookService.list();
        } else {
            QueryChainWrapper<Book> queryWrapper = bookService.query()
                    .eq(query.getType() != null, "type", query.getType())
                    .eq(query.getAuthor() != null, "author", query.getAuthor())
                    .like(query.getName() != null, "name", query.getName()) // 建议用 like
                    .ge(query.getScore() != null, "score", query.getScore())
                    .gt(query.getStock() != null, "stock", query.getStock());

            if (query.getSorts() != null && !query.getSorts().isEmpty()){
                for (BookQuery.Sort sort : query.getSorts()) {
                    queryWrapper.orderBy(true, sort.getAsc(), sort.getField());
                }
            }
            books = queryWrapper.list();
        }

        // 2. 【关键逻辑】检查结果
        // 如果没查到书，或者查到的所有书库存都 <= 0
        boolean isOutOfStock = books.isEmpty() || books.stream().allMatch(b -> b.getStock() <= 0);

        if (isOutOfStock) {
            // 3. 直接在 Java 内部调用推荐逻辑（无需 AI 再次发起请求）
            List<Book> recommendedBooks = internalRecommendBooks();

            // 4. 构造一个包含“遗憾通知”和“推荐列表”的复合结果
            Map<String, Object> result = new HashMap<>();
            result.put("status", "NOT_FOUND_OR_NO_STOCK");
            result.put("message", "用户查询的书籍不存在或库存为0。");
            result.put("original_query_result", books); // 保留原始空结果或0库存结果
            result.put("recommendations", recommendedBooks); // 附带推荐列表

            try {
                return objectMapper.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                return "查询无果且推荐失败";
            }
        }

        // 5. 如果有库存，正常返回
        try {
            return objectMapper.writeValueAsString(books);
        } catch (JsonProcessingException e) {
            return "JSON序列化错误";
        }
    }

    private List<Book> internalRecommendBooks() {
        return bookService.list(
                new LambdaQueryWrapper<Book>()
                        .gt(Book::getStock, 0)
                        .orderByDesc(Book::getScore)
                        .last("LIMIT 3")
        );
    }


    @Tool(description = "生成预约单，返回单号。仅当书籍有库存时才能预约成功。")
    @Transactional
    public Integer queryBookReservation(@ToolParam(description = "书籍名称") String name,
                                        @ToolParam(description = "借阅人姓名") String readerName,
                                        @ToolParam(description = "借阅人电话") String phone,
                                        @ToolParam(description = "备注", required = false) String remark){

        // 1. 根据书名查询书籍（假设书名唯一）
        Book book = bookService.getOne(new LambdaQueryWrapper<Book>().eq(Book::getName, name));
        if (book == null) {
            throw new IllegalArgumentException("未找到书名为《" + name + "》的书籍，请确认书名是否正确。");
        }

        // 2. 检查库存
        if (book.getStock() <= 0) {
            throw new IllegalStateException("《" + name + "》当前库存不足，无法预约。");
        }

        // 3. 扣减库存
        book.setStock(book.getStock() - 1);
        boolean updateSuccess = bookService.updateById(book);
        if (!updateSuccess) {
            throw new RuntimeException("库存更新失败，请重试。");
        }
        BookReservation bookReservation = new BookReservation();
        bookReservation.setBookName(name);
        bookReservation.setReaderName(readerName);
        bookReservation.setPhone(phone);
        bookReservation.setRemark(remark);
        bookReservationService.save(bookReservation);
        return bookReservation.getId();
    }
}
