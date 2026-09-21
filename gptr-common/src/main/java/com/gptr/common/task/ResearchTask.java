package com.gptr.common.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 研究任务（对应 {@code tasks} 表）。
 *
 * <p>任务即队列行：{@code status='PENDING'} 即等待出队，出队 = 状态迁移 + 租约分配
 * （原子 SQL）。
 */
@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ResearchTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 提交幂等键（可空，唯一） */
    @Column(name = "client_key", length = 64, unique = true)
    private String clientKey;

    /** 研究问题 */
    @Column(nullable = false, columnDefinition = "text")
    private String query;

    /** 引擎配置 JSON：source / model / max_sections / guidelines 等 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String config;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status = TaskStatus.PENDING;

    /** 0..100，默认 50，出队排序用 */
    @Column(nullable = false)
    private int priority = 50;

    /** 当前尝试次数（worker 崩溃重拾 +1） */
    @Column(nullable = false)
    private int attempt;

    /** 任务级最大尝试，默认 3 */
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    /** 步骤上限，默认 100 */
    @Column(name = "step_budget", nullable = false)
    private int stepBudget = 100;

    /** 时长上限（秒），默认 1800 */
    @Column(name = "time_budget_seconds", nullable = false)
    private int timeBudgetSeconds = 1800;

    /** 成本上限（美元），默认 10.0000 */
    @Column(name = "cost_budget_usd", nullable = false, precision = 10, scale = 4)
    private BigDecimal costBudgetUsd = new BigDecimal("10.0000");

    /** 已核算成本 */
    @Column(name = "cost_spent_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal costSpentUsd = BigDecimal.ZERO;

    /** 已用步骤 */
    @Column(name = "steps_used", nullable = false)
    private int stepsUsed;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /** 硬截止 = 创建时间 + time_budget_seconds，超时强制终止 */
    @Column(name = "deadline_at", nullable = false)
    private OffsetDateTime deadlineAt;

    /** worker 租约令牌 */
    @Column(name = "owner_token")
    private UUID ownerToken;

    /** 租约到期时间 */
    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    /** 报告在 MinIO 的对象引用（本地降级 local://path） */
    @Column(name = "result_ref")
    private String resultRef;

    /** 预算耗尽时是否保留部分结果 */
    @Column(name = "partial_result", nullable = false)
    private boolean partialResult;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_detail")
    private String errorDetail;

    /** 乐观锁版本 */
    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = TaskStatus.PENDING;
        }
        if (deadlineAt == null && timeBudgetSeconds > 0) {
            deadlineAt = now.plusSeconds(timeBudgetSeconds);
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
