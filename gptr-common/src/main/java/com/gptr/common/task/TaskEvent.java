package com.gptr.common.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 任务事件（对应 {@code task_events} 表，append-only 审计日志）。
 *
 * <p>任务内 {@code seq} 递增（由应用层计算），{@code UNIQUE(task_id, seq)}。
 */
@Entity
@Table(
        name = "task_events",
        uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "seq"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TaskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private ResearchTask task;

    /** 任务内事件序号（1 起递增） */
    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TaskEventType type;

    /** 阶段（STAGE_STARTED / STAGE_COMPLETED 时非空） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskStage stage;

    /** 事件负载 JSON（预留 traceId / spanId 字段） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
