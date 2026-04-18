package com.xdu.aibot.config;

import com.alibaba.cloud.ai.memory.redis.RedissonRedisChatMemoryRepository;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisMemoryConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;
    @Value("${spring.data.redis.port}")
    private int redisPort;
    @Value("${spring.data.redis.password}")
    private String redisPassword;

    /**
     * 共享的 RedissonClient，供 RedisSaver（ReactAgent记忆）等组件使用
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setPassword(redisPassword.isEmpty() ? null : redisPassword)
                .setDatabase(0);
        return Redisson.create(config);
    }

    /**
     * ChatMemory 使用的 Redis 仓储（内部自己创建 Redisson 连接）
     */
    @Bean
    public RedissonRedisChatMemoryRepository redisChatMemoryRepository() {
        return RedissonRedisChatMemoryRepository.builder()
                .host(redisHost)
                .port(redisPort)
                .password(redisPassword)
                .build();
    }
}