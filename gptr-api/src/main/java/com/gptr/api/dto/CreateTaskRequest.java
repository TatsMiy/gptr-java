package com.gptr.api.dto;

/**
 * 创建任务请求。
 *
 * @param query     研究问题（必填）
 * @param config    引擎配置 JSON（可空，默认 "{}"）
 * @param clientKey 提交幂等键（可空）
 */
public record CreateTaskRequest(String query, String config, String clientKey) {
}
