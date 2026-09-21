package com.gptr.common.service;

import java.time.OffsetDateTime;

/**
 * 创建任务的命令参数。
 *
 * @param query     研究问题（必填）
 * @param config    引擎配置 JSON（可空，默认 "{}"）
 * @param clientKey 提交幂等键（可空）
 */
public record CreateTaskCommand(String query, String config, String clientKey) {
}
