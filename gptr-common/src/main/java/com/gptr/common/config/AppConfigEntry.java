package com.gptr.common.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 全局运行配置行（{@code app_config}，V9）。
 *
 * <p>worker 启动时读取覆盖客户端装配默认值；改动**保存后重启 worker 生效**
 * （无运行时热替换——任务不可变 + 客户端单例原子替换风险）。
 * {@code secret} 行只允许写入与"已设置"查询，API 永不回显明文。
 */
@Entity
@Table(name = "app_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppConfigEntry {

    @Id
    @Column(length = 64)
    private String key;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, columnDefinition = "text")
    private String value;

    @Column(nullable = false)
    private boolean secret;

    @Column(nullable = false)
    private int version;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void bump() {
        version = version + 1;
        updatedAt = OffsetDateTime.now();
    }
}
