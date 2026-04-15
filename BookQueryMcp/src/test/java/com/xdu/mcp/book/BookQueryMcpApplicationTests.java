package com.xdu.mcp.book;


import com.xdu.mcp.book.tools.BookTools;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BookQueryMcpApplicationTests {
    @Resource
    private BookTools bookTools;

    @Test
    public void testQueryBook() {
        bookTools.queryBook("科幻", null, null, null, null);
    }

}
