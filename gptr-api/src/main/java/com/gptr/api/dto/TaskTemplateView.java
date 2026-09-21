package com.gptr.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.common.task.ResearchTask;

import java.util.Map;
import java.util.UUID;

/**
 * OBS-2.5 Fork 蓝图：{@code GET /tasks/{id}/template}。
 *
 * <p><b>config 白名单的唯一例外</b>：常规视图（TaskView 列表/详情）永不回传 config；
 * 仅本端点按需返回完整 config（含 query），供「复制为新任务」回填提交弹窗。
 * 目的明确 = clone 蓝图：新任务独立运行，原任务事件链原样保留。
 *
 * @param id     源任务 id
 * @param query  源任务研究问题
 * @param config 源任务引擎配置（JSON 对象；解析失败为空对象）
 */
public record TaskTemplateView(UUID id, String query, Map<String, Object> config) {

    public static TaskTemplateView from(ResearchTask t, ObjectMapper mapper) {
        Map<String, Object> cfg;
        try {
            var node = mapper.readTree(t.getConfig() == null ? "{}" : t.getConfig());
            cfg = mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            cfg = Map.of();
        }
        return new TaskTemplateView(t.getId(), t.getQuery(), cfg);
    }
}
