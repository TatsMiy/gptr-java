package com.gptr.engine.epoc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SourceRanker} 的**纯函数**部分（分档解析 + 重排）。
 *
  * <p>不含 LLM 调用：{@code rank} 与模型的交互靠"**失败即回退原序**"的契约保证，
  * 端到端行为另跑真实任务。
  * 这样的划分使本测试**无网络、无 key、`mvn test` 全自动**。
 */
class SourceRankerTest {

    @Test
    @DisplayName("high 序号转 0-based")
    void parseHighIndicesConvertsToZeroBased() {
        assertEquals(List.of(0, 2), DeepResearchPrompts.parseHighIndices("{\"high\":[1,3]}", 5));
    }

    @Test
    @DisplayName("越界 / 重复 / 非整数一律剔除")
    void parseHighIndicesFiltersInvalid() {
        assertEquals(List.of(1),
                DeepResearchPrompts.parseHighIndices("{\"high\":[2,2,9,0,-1]}", 3));
    }

    @Test
    @DisplayName("接受裸数组")
    void parseHighIndicesAcceptsBareArray() {
        assertEquals(List.of(0), DeepResearchPrompts.parseHighIndices("[1]", 2));
    }

    @Test
    @DisplayName("无法解析 → 空列表；且**只认 high 键**（不串用 curate 的 kept）")
    void parseHighIndicesRejectsGarbageAndForeignKeys() {
        assertTrue(DeepResearchPrompts.parseHighIndices("not json", 3).isEmpty());
        assertTrue(DeepResearchPrompts.parseHighIndices("{\"kept\":[1]}", 3).isEmpty());
        assertTrue(DeepResearchPrompts.parseHighIndices("{\"high\":[]}", 3).isEmpty());
    }

    @Test
    @DisplayName("重排：high 前置、normal 随后，两档各自保持原序")
    void reorderPutsHighFirstKeepingGroupOrder() {
        List<String> items = List.of("a", "b", "c", "d");
        SourceRanker.Priority p = new SourceRanker.Priority(List.of(2, 0), List.of(1, 3));
        assertEquals(List.of("c", "a", "b", "d"), SourceRanker.reorder(items, p));
    }

    @Test
    @DisplayName("无分档结果 → 原样返回（调用方无需判空分支）")
    void reorderReturnsSameListWithoutPriority() {
        List<String> items = List.of("a", "b");
        assertSame(items, SourceRanker.reorder(items, null));
    }

    @Test
    @DisplayName("候选 <2 条 → 不调用 LLM、返回 null（回退原序）")
    void rankSkipsWhenFewerThanTwoCandidates() {
        assertNull(SourceRanker.rank(List.of("https://a.example"), "q", null, c -> { }));
        assertNull(SourceRanker.rank(List.of(), "q", null, c -> { }));
        assertNull(SourceRanker.rank(null, "q", null, c -> { }));
    }
}
