package com.gptr.integration.storage;

import java.util.UUID;

/**
 * 报告产物存储抽象。
 *
 * <p>{@code put} 返回 result_ref（{@code minio://bucket/key} 或 {@code local://path}），
 * 写入 {@code tasks.result_ref}。
 */
public interface ReportStorage {

    /** 保存报告内容，返回 result_ref。 */
    String put(UUID taskId, String content);

    /** 按 result_ref 读取报告内容。 */
    String get(String ref);

    /** 按 result_ref 删除报告。 */
    void delete(String ref);
}
