package com.gptr.worker;

import com.gptr.common.repository.TaskCheckpointRepository;
import com.gptr.common.repository.TaskRepository;
import com.gptr.common.service.EventLogWriter;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
import com.gptr.engine.ResearchEngineFactory;
import com.gptr.integration.storage.ReportStorage;
import com.gptr.worker.budget.BudgetService;
import com.gptr.worker.queue.TaskDequeuer;
import com.gptr.worker.webhook.WebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务轮询调度器：定期尝试出队，槽位（concurrency）内有空余就提交执行。
 *
 * <p>出队是原子的（FOR UPDATE SKIP LOCKED），多 worker 实例并行轮询不会重复消费。
 */
@Component
@RequiredArgsConstructor
public class TaskWorker {

    private final TaskDequeuer dequeuer;
    private final ResearchEngineFactory engineFactory;
    private final TaskRepository taskRepository;
    private final TaskCheckpointRepository checkpointRepository;
    private final EventLogWriter eventLog;
    private final TaskService taskService;
    private final BudgetService budgetService;
    private final WebhookService webhookService;
    private final ReportStorage reportStorage;
    private final ExecutorService taskExecutor;

    @Value("${worker.concurrency:4}")
    private int concurrency;

    /** 租约秒数（守卫间隔=其 1/3）与守卫总开关（与 TaskExecutionJob 对齐）。 */
    @Value("${worker.lease-seconds:300}")
    private long leaseSeconds;

    @Value("${worker.lease-guard-enabled:true}")
    private boolean leaseGuardEnabled;

    private final AtomicInteger inflight = new AtomicInteger();

    @Scheduled(fixedDelayString = "${worker.poll-interval-ms:2000}")
    public void poll() {
        while (inflight.get() < concurrency) {
            Optional<ResearchTask> task = dequeuer.dequeue();
            if (task.isEmpty()) {
                break;
            }
            inflight.incrementAndGet();
            taskExecutor.submit(new TaskExecutionJob(
                    task.get().getId(),
                    task.get().getOwnerToken(),
                    engineFactory,
                    taskRepository,
                    checkpointRepository,
                    eventLog,
                    taskService,
                    dequeuer,
                    budgetService,
                    webhookService,
                    reportStorage,
                    inflight::decrementAndGet,
                    leaseSeconds,
                    leaseGuardEnabled));
        }
    }
}
