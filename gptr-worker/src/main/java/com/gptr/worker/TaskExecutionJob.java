package com.gptr.worker;

import com.gptr.common.engine.ResearchEngine;
import com.gptr.common.engine.StageResult;
import com.gptr.common.repository.TaskCheckpointRepository;
import com.gptr.common.repository.TaskRepository;
import com.gptr.common.service.EventLogWriter;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskCheckpoint;
import com.gptr.common.task.TaskEventType;
import com.gptr.common.task.TaskStage;
import com.gptr.common.task.TaskStateMachine;
import com.gptr.common.task.TaskStatus;
import com.gptr.integration.exception.PermanentApiException;
import com.gptr.integration.exception.QuotaApiException;
import com.gptr.integration.exception.TransientApiException;
import com.gptr.integration.storage.ReportStorage;
import com.gptr.engine.ResearchEngineFactory;
import com.gptr.worker.budget.BudgetExceededException;
import com.gptr.worker.budget.BudgetService;
import com.gptr.worker.queue.TaskDequeuer;
import com.gptr.worker.webhook.WebhookService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * 单个任务的执行作业（引擎主循环）。
 *
 * <p>底座持有控制循环：预算检查（步骤/时长/成本）→ 阶段开始事件 → 引擎执行 →
 * checkpoint 落库 → 阶段完成事件 → 成本核算 → 续租 → 取消检查；全部完成写报告并置
 * SUCCEEDED，预算超限优雅终止（partial_result），异常置 FAILED。
 */
@RequiredArgsConstructor
@Slf4j
public class TaskExecutionJob implements Runnable {

    /** 租约守卫续租间隔 = 租约秒数 / 该除数（约每秒续一次的三分之一节奏）。 */
    private static final long LEASE_GUARD_INTERVAL_DIVISOR = 3L;

    /** 续租间隔下限（毫秒）：租约极短时也不允许高频续租打库。 */
    private static final long LEASE_GUARD_MIN_INTERVAL_MS = 1000L;

    private final UUID taskId;
    private final UUID ownerToken;
    private final ResearchEngineFactory engineFactory;
    private final TaskRepository taskRepository;
    private final TaskCheckpointRepository checkpointRepository;
    private final EventLogWriter eventLog;
    private final TaskService taskService;
    private final TaskDequeuer dequeuer;
    private final BudgetService budgetService;
    private final WebhookService webhookService;
    private final ReportStorage reportStorage;
    private final Runnable onDone;

    /** 租约配置（worker.lease-seconds；守卫间隔按其 1/3 计算，下限 1s）。 */
    private final long leaseSeconds;
    /** 阶段内续租守卫总开关（worker.lease-guard-enabled，默认 true）。 */
    private final boolean leaseGuardEnabled;

    @Override
    public void run() {
        try {
            execute();
        } catch (BudgetExceededException e) {
            // 预算超限：优雅终止，保留已完成的 checkpoint（partial_result=true）
            taskService.budgetExceeded(taskId, ownerToken, e.getBudgetKind());
        } catch (QuotaApiException e) {
            // 配额耗尽（重试/降级均无效）→ 明确错误码
            taskService.fail(taskId, ownerToken, "QUOTA_EXHAUSTED", e.getMessage());
        } catch (TransientApiException e) {
            // 瞬时错误重试/降级全部耗尽
            taskService.fail(taskId, ownerToken, "TRANSIENT_EXHAUSTED", e.getMessage());
        } catch (PermanentApiException e) {
            // 永久错误（key 失效/配置错）不重试
            taskService.fail(taskId, ownerToken, "PERMANENT_FAILURE", e.getMessage());
        } catch (Exception e) {
            taskService.fail(taskId, ownerToken, "WORKER_ERROR",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            // 任务已终态（成功/失败/预算终止）→ 触发 webhook 通知（若有 callbackUrl）
            try {
                notifyWebhookIfTerminal();
            } catch (Exception ignored) {
                // webhook 通知失败不影响任务状态
            }
            onDone.run();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper(); // sink payload 组装（线程安全）

    private void execute() throws Exception {
        ResearchTask task = taskRepository.findById(taskId).orElse(null);
        // 租约已被回收（reaper 重置）或任务不存在 → 放弃执行
        if (task == null || !ownerToken.equals(task.getOwnerToken())) {
            return;
        }

        // DISPATCHED：本 worker 拾取并开始执行（与执行绑定，避免出队后崩溃导致事件丢失/重复）
        eventLog.append(taskId, TaskEventType.DISPATCHED, null, null);

        ResearchEngine engine = engineFactory.create(task);
        // 观测活动事件接线（ACTIVITY 独立事务 + 乐观 seq）；观测失败绝不影响任务
        engine.setActivitySink((stage, kind, label, detail) -> {
            try {
                ObjectNode obj = MAPPER.createObjectNode();
                obj.put("kind", kind == null ? "" : kind);
                obj.put("label", label == null ? "" : label);
                obj.set("detail", MAPPER.valueToTree(detail == null ? java.util.Map.of() : detail));
                eventLog.appendActivity(taskId, stage, obj.toString());
            } catch (Exception e) {
                log.warn("activity event dropped for task {} (kind={}): {}", taskId, kind,
                        e.getMessage());
            }
        });
        for (TaskStage stage : engine.stages()) {
            if (isCancelled()) {
                return;
            }
            // 预算检查：步骤 +1、时长、成本（任一超限 → BUDGET_<KIND> 优雅终止）
            budgetService.checkBeforeStage(taskId, ownerToken);

            eventLog.append(taskId, TaskEventType.STAGE_STARTED, stage, null);
            StageResult result = runStageWithLeaseGuard(stage, engine);
            // 1（脑裂收口）：收尾顺序前置续租——先确认租约仍属自己，再写
            // checkpoint/事件/成本；续租失败（reaper 已回收/被 requeue）→ 不写任何
            // 阶段产物直接放弃（任务已 PENDING 由新 worker 从零重跑；重跑前必须清旧 checkpoint）
            if (!dequeuer.renewLease(taskId, ownerToken)) {
                log.warn("lease lost after stage {} for task {}: abort without writing "
                        + "checkpoint/events/cost", stage, taskId);
                return;
            }
            checkpointRepository.save(new TaskCheckpoint(taskId, stage, result.payload(), null));
            eventLog.append(taskId, TaskEventType.STAGE_COMPLETED, stage, result.payload());
            budgetService.recordCost(taskId, ownerToken, result.costUsd());
            // 成本核算后即时复查（deep 图内成本整段入账，超限立即优雅终止）
            budgetService.checkCostLimit(taskId, ownerToken);
            if (isCancelled()) {
                return;
            }
        }

        // 评审补强：全阶段完成后、写报告与置成功前做最后一次续租确认——被 reaper 回收
        // 的 worker 干净退出（不写孤儿报告文件）；completeSuccess 的 ownerToken fence
        // 仍是兜底（fence 静默 return，不会抛状态机异常），此处提前退出消除竞态窗口
        if (!dequeuer.renewLease(taskId, ownerToken)) {
            log.warn("lease lost before final report for task {}: abort without success", taskId);
            return;
        }
        String report = engine.finalReport();
        String ref = writeReport(report);
        taskService.completeSuccess(taskId, ownerToken, ref);
    }

    /**
     * 阶段内租约守护——图/长阶段（含 mock delay 与真实深研图）阻塞期间由
     * 守护虚拟线程定期续租，防止 LeaseReaper 误判超时回收造成新旧 worker 双执行。
     *
     * <p>生命周期纪律：finally 必停守护（主路径异常也不后台空转）；守护续租失败只
     * 记录不打断阶段执行（invoke 无法安全中断），收尾由上面的"前置续租"fence 收敛。
     */
    private StageResult runStageWithLeaseGuard(TaskStage stage, ResearchEngine engine)
            throws Exception {
        if (!leaseGuardEnabled) {
            return engine.runStage(stage);
        }
        long intervalMs = Math.max(leaseSeconds * 1000L / LEASE_GUARD_INTERVAL_DIVISOR,
                LEASE_GUARD_MIN_INTERVAL_MS);
        java.util.concurrent.atomic.AtomicBoolean running =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread guard = Thread.ofVirtual().name("lease-guard-" + taskId).start(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    break; // 显式中断 → 立即退出
                }
                if (!running.get()) {
                    break;
                }
                try {
                    if (!dequeuer.renewLease(taskId, ownerToken)) {
                        log.warn("lease guard: renew failed for task {} (reaped/requeued)",
                                taskId);
                        break;
                    }
                } catch (Exception e) {
                    log.warn("lease guard: renew error for task {}: {}", taskId, e.getMessage());
                    break;
                }
            }
        });
        try {
            return engine.runStage(stage);
        } finally {
            running.set(false);
            guard.interrupt(); // 必须显式中断，避免后台泄露
        }
    }

    private boolean isCancelled() {
        return taskRepository.findById(taskId)
                .map(t -> t.getStatus() == TaskStatus.CANCELLED)
                .orElse(true);
    }

    private String writeReport(String content) {
        return reportStorage.put(taskId, content);
    }

    /** 任务已终态且 config 配置了 callbackUrl 时，创建 webhook 投递记录。 */
    private void notifyWebhookIfTerminal() {
        ResearchTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !TaskStateMachine.isTerminal(task.getStatus())) {
            return;
        }
        String callbackUrl = callbackUrlFrom(task.getConfig());
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return;
        }
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("taskId", taskId.toString());
        payload.put("status", task.getStatus().name());
        if (task.getErrorCode() != null) {
            payload.put("errorCode", task.getErrorCode());
        }
        if (task.getResultRef() != null) {
            payload.put("resultRef", task.getResultRef());
        }
        webhookService.schedule(taskId, callbackUrl, payload.toString());
    }

    private static String callbackUrlFrom(String config) {
        try {
            JsonNode root = new ObjectMapper().readTree(config == null ? "{}" : config);
            JsonNode url = root.path("callbackUrl");
            return url.isTextual() ? url.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
