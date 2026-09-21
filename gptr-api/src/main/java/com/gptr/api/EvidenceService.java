package com.gptr.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.api.dto.EvidenceView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OBS-2.5：证据库只读读取器（graph_checkpoints 终态 evidenceBank）。
 *
 * <p>原则：轻量 telemetry 走 WS/ACTIVITY，重度快照
 * （原文 quote）按需拉取——本服务一次 SQL + 白名单字段抽取，不引入引擎依赖。
 *
 * <p>task 存在性由 controller 层经 TaskService 校验；本服务只回答"有没有证据"。
 */
@Service
@RequiredArgsConstructor
public class EvidenceService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 读取任务 RESEARCH 图 checkpoint 中的证据库；无 checkpoint/无证据 → 空视图。 */
    public EvidenceView loadEvidence(UUID taskId) {
        List<String> states = jdbc.query(
                "SELECT state_json FROM graph_checkpoints WHERE thread_id = ?",
                (rs, i) -> rs.getString("state_json"),
                taskId.toString());
        List<EvidenceView.Note> notes = new ArrayList<>();
        for (String stateJson : states) {
            collectNotes(stateJson, notes);
        }
        return new EvidenceView(notes.size(), notes);
    }

    private void collectNotes(String stateJson, List<EvidenceView.Note> out) {
        try {
            JsonNode bank = mapper.readTree(stateJson).path("evidenceBank");
            if (!bank.isArray()) {
                return;
            }
            for (JsonNode item : bank) {
                // checkpoint Map-Jackson：evidenceBank 元素 = EvidenceNote JSON 字符串
                // （防御：个别元素若已是对象也能读）
                JsonNode n = item.isTextual() ? mapper.readTree(item.asText()) : item;
                if (n == null || !n.isObject()) {
                    continue;
                }
                out.add(new EvidenceView.Note(
                        n.path("idx").asInt(-1),
                        n.path("depth").asInt(0),
                        n.path("round").asInt(0),
                        n.path("queryIdx").asInt(-1),
                        n.path("queryText").asText(""),
                        n.path("insight").asText(""),
                        n.path("quote").asText(""),
                        n.path("sourceUrl").asText("")));
            }
        } catch (Exception ignored) {
            // 单条 checkpoint 解析失败不致命（终态损坏仍可查其它来源）
        }
    }
}
