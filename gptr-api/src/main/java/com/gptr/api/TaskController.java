package com.gptr.api;

import com.gptr.api.dto.CreateTaskRequest;
import com.gptr.api.dto.CreateTaskResponse;
import com.gptr.api.dto.EvidenceView;
import com.gptr.api.dto.TaskEventView;
import com.gptr.api.dto.TaskStatsView;
import com.gptr.api.dto.TaskTemplateView;
import com.gptr.api.dto.TaskView;
import com.gptr.common.exception.TaskNotFoundException;
import com.gptr.common.service.CreateTaskCommand;
import com.gptr.common.service.TaskService;
import com.gptr.common.service.TaskStats;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskEvent;
import com.gptr.common.task.TaskStateTransitionException;
import com.gptr.common.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 任务 API（观测台入口）。
 *
 * <pre>
 * POST /api/v1/tasks              提交任务 → 202 {taskId, status}
 * GET  /api/v1/tasks?status=&offset=&limit=  任务列表（created_at DESC，默认 30，上限 100）
 * GET  /api/v1/tasks/stats        近 24h 统计（tasks24h / cost24hUsd / byStatus；API 5s 缓存）
 * GET  /api/v1/tasks/{id}         查询状态
 * GET  /api/v1/tasks/{id}/template Fork 蓝图（query+config；config 白名单唯一例外）
 * GET  /api/v1/tasks/{id}/evidence 证据库（RESEARCH checkpoint 只读，OBS-2.5）
 * GET  /api/v1/tasks/{id}/report  最终研报正文（仅 SUCCEEDED，text/markdown）
 * POST /api/v1/tasks/{id}/cancel  取消
 * POST /api/v1/tasks/{id}/retry   运维重试（仅 FAILED）
 * GET  /api/v1/tasks/{id}/events  事件流
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    /** 观测台统计缓存（5s，24h 窗口 + API 层短缓存，避免高频刷库）。 */
    private static final long STATS_CACHE_MS = 5000;
    private static final long STATS_WINDOW_HOURS = 24;

    private final TaskService taskService;
    private final com.gptr.integration.storage.ReportStorage reportStorage;
    private final EvidenceService evidenceService;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    /** 最近统计快照缓存槽（volatile：并发双算无害，下一 5s 窗口收敛）。 */
    private volatile CacheSlot statsCache;

    private record CacheSlot(TaskStatsView view, long atMillis) {
    }

    @PostMapping
    public ResponseEntity<CreateTaskResponse> create(@RequestBody CreateTaskRequest req) {
        ResearchTask task = taskService.create(
                new CreateTaskCommand(req.query(), req.config(), req.clientKey()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CreateTaskResponse(task.getId(), task.getStatus()));
    }

    /** OBS-1：任务列表（可带 status 过滤；offset/limit 翻页）。 */
    @GetMapping
    public List<TaskView> list(@RequestParam(required = false) TaskStatus status,
                               @RequestParam(defaultValue = "0") int offset,
                               // limit 不传时传 0，由 TaskService 的单源默认兜底（避免此处再写一份 30）
                               @RequestParam(defaultValue = "0") int limit) {
        return taskService.list(status, offset, limit).stream().map(TaskView::from).toList();
    }

    /**
     * OBS-2：读取最终研报正文（仅 SUCCEEDED 且有 result_ref；read-only）。
     *
     * <p>返回 {@code text/markdown}；ref 只取自 DB（tasks.result_ref），不接受客户端路径。
     */
    @GetMapping("/{id}/report")
    public ResponseEntity<String> report(@PathVariable UUID id) {
        ResearchTask task = taskService.get(id);
        String ref = task.getResultRef();
        if (task.getStatus() != com.gptr.common.task.TaskStatus.SUCCEEDED || ref == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("no report yet for task " + id + " (status=" + task.getStatus() + ")");
        }
        try {
            String body = reportStorage.get(ref);
            return ResponseEntity.ok()
                    .header("Content-Type", "text/markdown; charset=UTF-8")
                    .body(body);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("report missing for task " + id + ": " + e.getMessage());
        }
    }

    /** OBS-1：近 24h 统计（5s 缓存）。 */
    @GetMapping("/stats")
    public TaskStatsView stats() {
        long now = System.currentTimeMillis();
        CacheSlot slot = statsCache;
        if (slot == null || now - slot.atMillis() > STATS_CACHE_MS) {
            TaskStats s = taskService.statsSince(OffsetDateTime.now().minusHours(STATS_WINDOW_HOURS));
            slot = new CacheSlot(TaskStatsView.from(s), now);
            statsCache = slot;
        }
        return slot.view();
    }

    @GetMapping("/{id}")
    public TaskView get(@PathVariable UUID id) {
        return TaskView.from(taskService.get(id));
    }

    /** OBS-2.5 Fork：返回源任务 query + 完整 config（config 白名单唯一例外，clone 蓝图用）。 */
    @GetMapping("/{id}/template")
    public TaskTemplateView template(@PathVariable UUID id) {
        return TaskTemplateView.from(taskService.get(id), mapper);
    }

    /** OBS-2.5 证据库：RESEARCH checkpoint 终态 evidenceBank（只读；无证据 → 空视图）。 */
    @GetMapping("/{id}/evidence")
    public EvidenceView evidence(@PathVariable UUID id) {
        taskService.get(id); // 404 语义：任务不存在
        return evidenceService.loadEvidence(id);
    }

    @PostMapping("/{id}/cancel")
    public TaskView cancel(@PathVariable UUID id) {
        return TaskView.from(taskService.cancel(id));
    }

    @PostMapping("/{id}/retry")
    public TaskView retry(@PathVariable UUID id) {
        return TaskView.from(taskService.retry(id));
    }

    @GetMapping("/{id}/events")
    public List<TaskEventView> events(@PathVariable UUID id) {
        return taskService.listEvents(id).stream().map(e -> TaskEventView.from(id, e)).toList();
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<String> notFound(TaskNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    /** 乐观锁冲突（cancel/retry 与 worker 并发写同一任务）→ 语义化 409 + 提示重查。 */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<String> optimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("concurrent modification detected, re-query the task: " + e.getMessage());
    }

    @ExceptionHandler(TaskStateTransitionException.class)
    public ResponseEntity<String> badTransition(TaskStateTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
