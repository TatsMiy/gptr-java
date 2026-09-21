package com.gptr.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.api.dto.TaskEventView;
import com.gptr.common.repository.TaskEventRepository;
import com.gptr.common.task.TaskEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务事件实时订阅（WS 通道）：{@code WS /ws/tasks/{taskId}}。
 *
 * <p>订阅后由 {@link #pushNewEvents}（@Scheduled 轮询 DB 事件表）推送新事件——
 * worker 与 api 是独立进程，通过数据库事件日志解耦，轮询是跨进程最简单可靠的方案。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventsWebSocketHandler extends TextWebSocketHandler {

    private final TaskEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    /** taskId → (session → 已推送的最大 seq)。 */
    private final Map<UUID, Map<WebSocketSession, Integer>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID taskId = extractTaskId(session);
        if (taskId == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        // 订阅时 lastSeq=0：下一次轮询立即推送该任务全部现有事件（含 CREATED）
        subscribers.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>()).put(session, 0);
        log.info("ws subscribed task {} (sessions={})", taskId, subscribers.get(taskId).size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscribers.forEach((taskId, sessions) -> sessions.remove(session));
    }

    /** 轮询推送：每 1s 检查各订阅任务的新事件。 */
    @Scheduled(fixedDelayString = "${gptr.ws.poll-interval-ms:1000}")
    public void pushNewEvents() {
        subscribers.forEach((taskId, sessions) -> {
            if (sessions.isEmpty()) {
                return;
            }
            List<TaskEvent> events = eventRepository.findByTaskIdOrderBySeqAsc(taskId);
            if (events.isEmpty()) {
                return;
            }
            int maxSeq = events.get(events.size() - 1).getSeq();
            for (Map.Entry<WebSocketSession, Integer> entry : sessions.entrySet()) {
                int lastSeq = entry.getValue();
                if (maxSeq <= lastSeq) {
                    continue;
                }
                try {
                    for (TaskEvent event : events) {
                        if (event.getSeq() > lastSeq) {
                            String json = objectMapper.writeValueAsString(TaskEventView.from(taskId, event));
                            entry.getKey().sendMessage(new TextMessage(json));
                        }
                    }
                    entry.setValue(maxSeq);
                } catch (Exception e) {
                    log.warn("ws push failed for task {}: {}", taskId, e.getMessage());
                }
            }
        });
    }

    private static UUID extractTaskId(WebSocketSession session) {
        var path = session.getUri() == null ? "" : session.getUri().getPath();
        String[] parts = path.split("/");
        if (parts.length < 3) {
            return null;
        }
        try {
            return UUID.fromString(parts[parts.length - 1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
