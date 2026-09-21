package com.gptr.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Worker 线程池配置（并发 = 同时运行任务数，默认 4）。 */
@Configuration
public class WorkerConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService taskExecutor(@Value("${worker.concurrency:4}") int concurrency) {
        return Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "gptr-worker");
            t.setDaemon(true);
            return t;
        });
    }
}
