package com.xdu.mcp.book;

import com.xdu.mcp.book.tools.BookTools;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@MapperScan("com.xdu.mcp.book.mapper")
public class BookQueryMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookQueryMcpApplication.class, args);
    }

}
