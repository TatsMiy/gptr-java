package com.gptr.benchmark.dims;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ABJudge 盲判对调回归测试（P0 修复）+ parseWinners 纯函数（swap 归因/坏值拒收）。
 */
class ABJudgeTest {

    private static final String MARKER_A = "REPORT-A-UNIQUE-MARKER";
    private static final String MARKER_B = "REPORT-B-UNIQUE-MARKER";
    private static final String Q = "问题？";

    private static String body(String user) {
        return user;
    }

    @Test
    void swapFalseKeepsAFirst() {
        String user = ABJudge.buildUser(Q, MARKER_A, MARKER_B, false);
        int posA = user.indexOf(MARKER_A);
        int posB = user.indexOf(MARKER_B);
        assertTrue(posA >= 0 && posB >= 0, "两篇都在场");
        assertTrue(posA < posB, "swap=false：A 应在 report1 位置");
        assertTrue(user.indexOf("---- report1 ----") < user.indexOf(MARKER_A));
    }

    @Test
    void swapTruePhysicallySwapsTexts() {
        String user = ABJudge.buildUser(Q, MARKER_A, MARKER_B, true);
        int posA = user.indexOf(MARKER_A);
        int posB = user.indexOf(MARKER_B);
        assertTrue(posA >= 0 && posB >= 0, "两篇都在场");
        // report1 段必须含 B 的文本：B 出现在 report1 标记之后、report2 标记之前
        int r1 = user.indexOf("---- report1 ----");
        int r2 = user.indexOf("---- report2 ----");
        assertTrue(r1 < posB && posB < r2, "swap=true：report1 位置应放 B 文本");
        assertTrue(r2 < posA, "swap=true：report2 位置应放 A 文本");
        assertFalse(posA < posB, "物理对调后 A 不得仍在 report1 段");
    }

    @Test
    void truncationCapAppliesPerSideAndKeepsOrderingAndMarker() {
        // 回归：护栏截断不得破坏 swap 物理对调，且必须保留显式截断标记（判官可见）。
        // 护栏是防御性上限，不应在日常触发——见 ABJudge.TRUNCATE_CAP 注释。
        String longA = "A".repeat(ABJudge.TRUNCATE_CAP + 500);
        String user = ABJudge.buildUser(Q, longA, MARKER_B, true);
        assertTrue(user.contains("...[truncated]"), "超长侧应带显式截断标记");
        assertFalse(user.contains(longA), "截断后不得整段在场");
        int r1 = user.indexOf("---- report1 ----");
        int r2 = user.indexOf("---- report2 ----");
        int posB = user.indexOf(MARKER_B);
        assertTrue(r1 < posB && posB < r2, "B 短文本全量在场且仍在 report1 段");
        // A 的截断残留必须落在 report2 段内，不得越过 report1 段
        assertTrue(user.indexOf("A".repeat(100)) > r2, "A 截断残段在 report2 段");
    }

    // ------------------------------------------------------------------
    // parseWinners 纯函数：swap 归因 / tie / 坏值整条拒收
    // ------------------------------------------------------------------

    private static final ObjectMapper M = new ObjectMapper();

    private static ObjectNode verdicts(ObjectNode o) {
        return o;
    }

    @Test
    void parseWinnersMapsReportNumberToSideAndAppliesSwap() {
        ObjectNode root = verdicts(M.createObjectNode());
        root.put("org_structure", "1");
        root.put("language_readability", "2");
        root.put("depth_insight", "tie");
        root.put("citation_discipline", "2");
        root.put("honesty_restraint", "1");
        root.put("overall", "1");
        // swap=false：1→A，2→B
        Map<String, String> w0 = ABJudge.parseWinners(root, ABJudge.WRITING_DIMENSIONS, false);
        assertEquals("A", w0.get("org_structure"), "report1=A");
        assertEquals("B", w0.get("language_readability"), "report2=B");
        assertEquals("tie", w0.get("depth_insight"));
        // swap=true：report1 位置放的是 B → 归因翻转
        Map<String, String> w1 = ABJudge.parseWinners(root, ABJudge.WRITING_DIMENSIONS, true);
        assertEquals("B", w1.get("org_structure"), "swap 后 report1=B");
        assertEquals("A", w1.get("language_readability"));
        assertEquals("tie", w1.get("depth_insight"), "tie 不翻转");
    }

    @Test
    void parseWinnersRejectsBadOrMissingValueEntirely() {
        // 坏值
        ObjectNode bad = verdicts(M.createObjectNode());
        for (String d : ABJudge.WRITING_DIMENSIONS) {
            bad.put(d, "1");
        }
        bad.put("overall", "winner"); // 非法值
        assertNull(ABJudge.parseWinners(bad, ABJudge.WRITING_DIMENSIONS, false),
                "坏值 → null（整条拒收，防注水）");
        // 缺维
        ObjectNode missing = verdicts(M.createObjectNode());
        for (String d : ABJudge.WRITING_DIMENSIONS) {
            if (!d.equals("overall")) {
                missing.put(d, "1");
            }
        }
        assertNull(ABJudge.parseWinners(missing, ABJudge.WRITING_DIMENSIONS, false),
                "缺维 → null");
        // 全 tie 合法
        ObjectNode ties = verdicts(M.createObjectNode());
        for (String d : ABJudge.WRITING_DIMENSIONS) {
            ties.put(d, "tie");
        }
        assertEquals("tie", ABJudge.parseWinners(ties, ABJudge.WRITING_DIMENSIONS, false).get("overall"));
    }

    @Test
    void writingDimensionsAreSixIncludingOverall() {
        assertEquals(6, ABJudge.WRITING_DIMENSIONS.size());
        assertTrue(ABJudge.WRITING_DIMENSIONS.contains("overall"));
        assertTrue(ABJudge.WRITING_DIMENSIONS.contains("honesty_restraint"));
        // 与旧四维不冲突（I-5 保留）
        assertEquals(4, ABJudge.DIMENSIONS.size());
    }
}
