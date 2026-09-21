package com.gptr.worker.budget;

import com.gptr.common.repository.TaskRepository;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 预算检查器（主循环 ①③）：
 *
 * <ul>
 *   <li>{@link #checkBeforeStage}：每阶段开始前——步骤 +1 并检查步骤/时长/成本上限，
 *       任一超限抛 {@link BudgetExceededException}（由执行作业映射为优雅终止）</li>
 *   <li>{@link #recordCost}：阶段完成后核算外部调用成本（累加 cost_spent_usd）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BudgetService {

    private final TaskRepository taskRepository;
    private final TaskService taskService;

    /** 阶段开始前检查：步骤 +1、检查步骤/时长/成本三上限。 */
    @Transactional
    public void checkBeforeStage(UUID taskId, UUID ownerToken) {
        ResearchTask task = taskRepository.findById(taskId).orElseThrow();
        if (!ownerToken.equals(task.getOwnerToken())) {
            return;
        }
        int steps = task.getStepsUsed() + 1;
        task.setStepsUsed(steps);
        taskRepository.save(task);
        if (steps > task.getStepBudget()) {
            throw new BudgetExceededException("STEP");
        }
        if (OffsetDateTime.now().isAfter(task.getDeadlineAt())) {
            throw new BudgetExceededException("TIME");
        }
        if (task.getCostSpentUsd().compareTo(task.getCostBudgetUsd()) >= 0) {
            throw new BudgetExceededException("COST");
        }
    }

    /** 阶段完成后核算成本。 */
    @Transactional
    public void recordCost(UUID taskId, UUID ownerToken, double costUsd) {
        taskService.recordCost(taskId, ownerToken, costUsd);
    }

    /**
     * 成本核算后的即时复查——超限立即抛 BUDGET_COST 优雅终止。
     * 补 checkBeforeStage 只在阶段边界检查的缺口（deep RESEARCH 图内成本在阶段结束
     * 才一次性入账，若不复查要等下个阶段才发现超支；最后一个阶段后则永不发现）。
     */
    @Transactional
    public void checkCostLimit(UUID taskId, UUID ownerToken) {
        ResearchTask task = taskRepository.findById(taskId).orElseThrow();
        if (!ownerToken.equals(task.getOwnerToken())) {
            return;
        }
        if (task.getCostSpentUsd().compareTo(task.getCostBudgetUsd()) >= 0) {
            throw new BudgetExceededException("COST");
        }
    }
}
