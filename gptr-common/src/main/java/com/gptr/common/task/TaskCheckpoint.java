package com.gptr.common.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 阶段检查点（对应 {@code checkpoints} 表）。
 *
 * <p>复合主键 (task_id, stage)：每完成一个阶段写一条记录；worker 崩溃重拾后
 * 查询已有 checkpoint 即可从下一阶段继续，而不是从头跑。
 * payload 为该阶段的完整中间结果。
 */
@Entity
@Table(name = "checkpoints")
@IdClass(TaskCheckpointId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TaskCheckpoint {

    @Id
    @Column(name = "task_id")
    private UUID taskId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStage stage;

    /** 阶段完整结果 JSON（如检索到的 URL 列表、上下文、草稿） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = OffsetDateTime.now();
    }
}
