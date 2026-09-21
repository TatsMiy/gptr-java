package com.gptr.common.repository;

import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务仓库（基础 CRUD 与查询；租约过期查询）。
 *
 * <p>原子出队 SQL（FOR UPDATE SKIP LOCKED）在 TaskDequeuer 中以 JdbcTemplate 实现。
 */
public interface TaskRepository extends JpaRepository<ResearchTask, UUID> {

    /** 按状态查询（按创建时间升序）。 */
    List<ResearchTask> findByStatusOrderByCreatedAtAsc(TaskStatus status);

    /** 租约过期（RUNNING 且 lease_until 早于给定时间）的任务，供 LeaseReaper 扫描。 */
    List<ResearchTask> findByStatusAndLeaseUntilBefore(TaskStatus status, OffsetDateTime time);

    /** 幂等键是否已存在。 */
    boolean existsByClientKey(String clientKey);

    /** 按幂等键查询。 */
    Optional<ResearchTask> findByClientKey(String clientKey);

    // ------------------------------------------------------------------
    // OBS-1 观测台查询（V7 索引 (status, created_at DESC)）
    // ------------------------------------------------------------------

    /** 最新全部任务（created_at DESC）。 */
    @Query("select t from ResearchTask t order by t.createdAt desc")
    List<ResearchTask> findLatest(Pageable pageable);

    /** 最新指定状态任务（created_at DESC）。 */
    @Query("select t from ResearchTask t where t.status = :status order by t.createdAt desc")
    List<ResearchTask> findLatestByStatus(@Param("status") TaskStatus status, Pageable pageable);

    /** 统计窗内任务总量（created_at 过滤，配 V7 索引前缀列）。 */
    @Query("select count(t) from ResearchTask t where t.createdAt >= :since")
    long countSince(@Param("since") OffsetDateTime since);

    /** 统计窗内任务状态分布。 */
    @Query("select t.status, count(t) from ResearchTask t "
            + "where t.createdAt >= :since group by t.status")
    List<Object[]> countGroupByStatusSince(@Param("since") OffsetDateTime since);

    /** 统计窗内累计花费（可为 null：窗内无任务）。 */
    @Query("select sum(t.costSpentUsd) from ResearchTask t where t.createdAt >= :since")
    BigDecimal sumCostSince(@Param("since") OffsetDateTime since);
}
