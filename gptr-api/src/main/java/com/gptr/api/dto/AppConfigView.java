package com.gptr.api.dto;

import java.time.OffsetDateTime;

/**
 * 配置项视图（{@code GET /api/v1/config}）。
 *
 * <p>secret 行永不携带明文值（value=null，仅 set 态）；非 secret 未覆盖时 value=null
 * 表示"未设置 = worker 用内置默认"。前端文案负责展示各键默认值。
 *
 * @param key         键（白名单内）
 * @param description 人读说明
 * @param secret      是否密钥（不回显明文）
 * @param set         是否已覆盖（app_config 有行）
 * @param value       当前覆盖值（secret 恒 null；未覆盖 null）
 * @param version     覆盖版本（0 = 未覆盖）
 * @param updatedAt   最近覆盖时间（null = 未覆盖）
 */
public record AppConfigView(
        String key,
        String description,
        boolean secret,
        boolean set,
        String value,
        int version,
        OffsetDateTime updatedAt) {
}
