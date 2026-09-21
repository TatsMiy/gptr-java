package com.gptr.engine.epoc;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 蒸馏并发闸：限制进程内**同时在跑**的「单页蒸馏」调用数（防 20k 长文 burst 打爆 API 的 TPM 额度）。
 *
 * <p><b>实例由装配层持有并注入</b>（{@code ResearchEngineFactory} 持有单例 → 进程内所有引擎实例
 * 共享同一个），因此它的容量必须来自与它同生命周期的<b>应用级</b>配置
 * （{@code gptr.engine.distill-concurrency}），而不是任务级 config。
 *
 * <p><b>为什么是包裹式 {@link #around}</b>：本闸的正确性依赖「acquire 与 release 作用于同一实例且成对」。
 * 项目曾两次正是在这里出错——一次 release 落到被替换掉的新实例上（许可永久泄漏），
 * 一次只改了 acquire 而漏改 release（那段代码上方还写着"已根治"）。
 * {@code around} 让「取许可 → 执行 → 归还」成为**不可分割的一次调用**，从结构上关闭写错的路径。
 *
 * <p>纯 Java、<b>不带 Spring 注解</b>：本类只管限流，生命周期交给装配层。
 */
public final class DistillGate {

    /** 容量上限，与 {@code EngineConfig} 对 distillConcurrency 的校验范围（1..8）保持一致。 */
    private static final int MAX_CONCURRENCY = 8;

    private final Semaphore permits;
    private final int concurrency;

    public DistillGate(int concurrency) {
        this.concurrency = Math.max(1, Math.min(MAX_CONCURRENCY, concurrency));
        this.permits = new Semaphore(this.concurrency);
    }

    /**
     * 包裹式执行：取许可 → 执行 → 归还，三者不可分割。
     *
     * <p><b>中断语义</b>：等待许可时被中断 → 恢复中断位并返回 {@code null}（与改造前一致）。
     * ⚠️ <b>调用方必须在重试循环头检查 {@code Thread.currentThread().isInterrupted()}</b>——
     * 否则这里因中断返回的 {@code null} 会被误判成「本次蒸馏失败」而多跑一轮，
     * 中断响应的时机被悄悄改变（实际危害有限：中断位已置位 ⇒ 再次 acquire 会立即再抛）。
     */
    public <T> T around(Supplier<T> task) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            return task.get();
        } finally {
            permits.release();
        }
    }

        /** 当前可用许可（供测试断言「无许可泄漏」）。 */
    public int availablePermits() {
        return permits.availablePermits();
    }

    /** 本闸的并发上限。 */
    public int concurrency() {
        return concurrency;
    }
}
