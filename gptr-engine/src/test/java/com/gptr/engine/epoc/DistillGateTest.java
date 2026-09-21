package com.gptr.engine.epoc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DistillGate} 并发契约测试。
 *
 * <p><b>本类存在的理由</b>：闸的正确性依赖「acquire 与 release 作用于同一实例且成对」，
 * 而项目在这里出过两次事（一次 release 落到被替换的新实例、一次只改 acquire 漏改 release，
 * 后者还带着"已根治"的注释）。那两次都**带着全绿的测试长期存活**——因为单测既不并发、
 * 也不会在 acquire 与 release 之间插一次配置变更。
 *
 * <p>所以三个断言都是**结构性**的，不是行为快照：
 * <ol>
 *   <li>并发上限**恰好**用满（防"闸被误写成串行"的假通过）；</li>
 *   <li>许可**全部归还**（专抓"release 落到别处 / 漏调"那类缺陷）；</li>
 *   <li>两个独立闸**互不阻塞**（防将来有人把它改回进程级 static 单例）。</li>
 * </ol>
 */
class DistillGateTest {

        /** 容量 2 时 5 个并发任务的峰值 **恰好 == 2**，且跑完许可全部归还。 */
    @Test
    void enforcesCapAndReturnsAllPermits() throws Exception {
        DistillGate gate = new DistillGate(2);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);      // 双栅栏之一：让 5 个线程同时启动
        CountDownLatch entered = new CountDownLatch(2);    // 前 2 个进入临界区后主线程才断言
        CountDownLatch proceed = new CountDownLatch(1);    // 双栅栏之二：断言完再放行，稳定复现峰值
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                gate.around(() -> {
                    int now = running.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    entered.countDown();
                    try {
                        proceed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    running.decrementAndGet();
                    return "ok";
                });
            });
            t.start();
            threads.add(t);
        }

        start.countDown();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "应有恰好 2 个任务同时进入临界区");
        assertEquals(2, peak.get(), "并发上限必须恰好用满（==2 而非 <=2：后者会把'闸被写成串行'放过）");

        proceed.countDown();
        for (Thread t : threads) {
            t.join(5000);
        }
        assertEquals(0, running.get(), "全部线程退出后不应有仍在临界区内的任务");
                // 无许可泄漏：若 release 落到别的实例或漏调，这里不会回到初始值
        assertEquals(2, gate.availablePermits(), "许可必须全部归还（专抓 release 落到别处/漏调）");
    }

        /** 两个独立闸不得互相阻塞（防将来改回"进程级 static 单例"而破坏测试隔离）。 */
    @Test
    void independentGatesDoNotBlockEachOther() throws Exception {
        DistillGate g1 = new DistillGate(1);
        DistillGate g2 = new DistillGate(1);
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch g2Done = new CountDownLatch(1);

        Thread t1 = new Thread(() -> g1.around(() -> {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        t1.start();
        Thread.sleep(50); // 让 t1 先占满 g1（g1 容量 1）

        Thread t2 = new Thread(() -> g2.around(() -> {
            g2Done.countDown();
            return null;
        }));
        t2.start();

        assertTrue(g2Done.await(2, TimeUnit.SECONDS), "两个独立闸不得互相阻塞（g1 占满时 g2 仍应可用）");

        hold.countDown();
        t1.join(3000);
        t2.join(3000);
        assertEquals(1, g1.availablePermits(), "g1 许可应已归还");
        assertEquals(1, g2.availablePermits(), "g2 许可应已归还");
    }

    /** 容量夹取：越界值被夹到 1..8（与 EngineConfig 的校验范围一致，防止闸被建成 0 或超大）。 */
    @Test
    void clampsConcurrencyIntoValidRange() {
        assertEquals(1, new DistillGate(0).concurrency());
        assertEquals(1, new DistillGate(-5).concurrency());
        assertEquals(8, new DistillGate(99).concurrency());
        assertEquals(8, new DistillGate(8).availablePermits());
    }
}
