package com.xdu.aibot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.xdu.aibot.mapper")
public class AIbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AIbotApplication.class, args);
    }

}
