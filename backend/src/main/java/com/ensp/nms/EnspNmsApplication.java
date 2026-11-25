package com.ensp.nms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 园区网工程预实施验证系统 Spring Boot 启动入口，开启调度、异步与 Web 容器。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15
 */

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class EnspNmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnspNmsApplication.class, args);
    }
}

