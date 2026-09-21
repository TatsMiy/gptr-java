package com.gptr.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * gptr-worker：任务队列消费者（出队、租约、引擎循环、checkpoint 回写）。
 *
 * <p>扫描 {@code com.gptr} 包加载 common 领域服务；JPA 仓库与实体位于
 * {@code com.gptr.common.*}，需显式指定扫描包。
 */
@SpringBootApplication(scanBasePackages = "com.gptr")
@EnableJpaRepositories(basePackages = "com.gptr.common.repository")
@EntityScan(basePackages = {"com.gptr.common.task", "com.gptr.common.config"})
@EnableScheduling
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
