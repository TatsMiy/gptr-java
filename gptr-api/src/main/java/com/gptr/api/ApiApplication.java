package com.gptr.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * gptr-api：REST 服务（提交/查询/取消/重试研究任务）+ WebSocket 事件订阅。
 *
 * <p>扫描 {@code com.gptr} 包加载 common 领域服务；JPA 仓库与实体位于
 * {@code com.gptr.common.*}，需显式指定扫描包。
 */
@SpringBootApplication(scanBasePackages = "com.gptr")
@EnableJpaRepositories(basePackages = "com.gptr.common.repository")
@EntityScan(basePackages = {"com.gptr.common.task", "com.gptr.common.config"})
@EnableScheduling
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
