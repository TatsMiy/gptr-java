package com.gptr.api.dto;

import java.util.List;

/**
 * 证据库只读视图：{@code GET /tasks/{id}/evidence}。
 *
 * <p>读取 RESEARCH 图终态 checkpoint（graph_checkpoints.state_json）中的
 * evidenceBank，白名单抽取卡片字段。quote 为存储级**完整**原文（≤2000 字符），
 * 前端按需折叠展示——重快照走按需拉取，不违反 ACTIVITY telemetry 瘦身条款。
 *
 * @param total 证据条数
 * @param notes 证据卡片（evidenceBank 原序）
 */
public record EvidenceView(int total, List<Note> notes) {

    /** 白名单卡片字段（与 EvidenceNote 存储 JSON 键一致，仅抽取不回吐未知键）。 */
    public record Note(
            int idx,
            int depth,
            int round,
            int queryIdx,
            String queryText,
            String insight,
            String quote,
            String sourceUrl) {
    }
}
