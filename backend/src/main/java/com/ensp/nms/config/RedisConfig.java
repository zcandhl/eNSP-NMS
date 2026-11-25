package com.ensp.nms.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Redis 连接与序列化配置，条件装配缓存模板。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15
 */

@Configuration
public class RedisConfig {

    @Bean
    @ConditionalOnProperty(name = "spring.data.redis.host", matchIfMissing = false)
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}

