package com.gptr.worker.webhook;

import com.gptr.common.repository.WebhookDeliveryRepository;
import com.gptr.common.task.WebhookDelivery;
import com.gptr.common.task.WebhookStatus;
import com.gptr.integration.security.UrlSecurity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Webhook 投递器：
 *
 * <ul>
 *   <li>{@link #schedule}：任务终态时创建 PENDING 投递记录</li>
 *   <li>{@link #deliverDue}（@Scheduled）：扫描到期记录投递，HMAC-SHA256 签名，
 *       失败指数退避重试（最多 maxAttempts 次），成功置 SENT、超限置 FAILED</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    /** 投递 HTTP 连接超时（秒）——短于引擎侧外呼：回调地址由客户提供，连不上应尽快失败。 */
    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    /** 单次投递请求超时（秒）。 */
    private static final int REQUEST_TIMEOUT_SECONDS = 10;

    /** 非 2xx 响应体进错误消息的截断长度（防止整个响应体写进 lastError）。 */
    private static final int ERROR_BODY_MAX_CHARS = 200;

    /** 指数退避的倍增因子（base × 2^(attempt-1)）。 */
    private static final int BACKOFF_MULTIPLIER = 2;

    private final WebhookDeliveryRepository repository;

    @Value("${gptr.webhook.secret:gptr-dev-secret}")
    private String secret;

    @Value("${gptr.webhook.max-attempts:5}")
    private int maxAttempts;

    @Value("${gptr.webhook.backoff-base-ms:1000}")
    private long backoffBaseMs;

    /** SSRF 防护：默认拒绝私网/环回回调地址；本机回调（测试/内网部署）显式开启。 */
    @Value("${gptr.webhook.allow-private-urls:false}")
    private boolean allowPrivateUrls;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build();

    /** 创建投递记录（幂等：同任务已有 PENDING 则跳过）。URL 经 SSRF 校验。 */
    @Transactional
    public void schedule(UUID taskId, String url, String payload) {
        try {
            UrlSecurity.assertSafeHttpUrl(url, allowPrivateUrls);
        } catch (IllegalArgumentException e) {
            log.warn("webhook url rejected (SSRF guard) for task {}: {}", taskId, e.getMessage());
            return; // 安全拒绝：不创建投递记录
        }
        boolean pendingExists = repository.findAll().stream()
                .anyMatch(d -> d.getTaskId().equals(taskId) && d.getStatus() == WebhookStatus.PENDING);
        if (pendingExists) {
            return;
        }
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setTaskId(taskId);
        delivery.setUrl(url);
        delivery.setPayload(payload);
        delivery.setStatus(WebhookStatus.PENDING);
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(OffsetDateTime.now());
        repository.save(delivery);
        log.info("webhook scheduled for task {} -> {}", taskId, url);
    }

    /** 扫描并投递到期的 PENDING 记录。 */
    @Scheduled(fixedDelayString = "${gptr.webhook.poll-interval-ms:2000}")
    @Transactional
    public void deliverDue() {
        List<WebhookDelivery> due = repository.findByStatusAndNextAttemptAtBefore(
                WebhookStatus.PENDING, OffsetDateTime.now());
        for (WebhookDelivery delivery : due) {
            deliver(delivery);
        }
    }

    private void deliver(WebhookDelivery delivery) {
        // C3-S10：投递前二次 SSRF 校验（schedule 与 deliver 之间存在时间窗，
        // DNS 状态可能变化）；拒绝则直接 FAILED（重试无意义）
        try {
            UrlSecurity.assertSafeHttpUrl(delivery.getUrl(), allowPrivateUrls);
        } catch (IllegalArgumentException e) {
            delivery.setStatus(WebhookStatus.FAILED);
            delivery.setLastError("webhook url rejected (SSRF guard): " + e.getMessage());
            repository.save(delivery);
            return;
        }
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String signature = sign(timestamp, delivery.getPayload());
            HttpRequest request = HttpRequest.newBuilder(URI.create(delivery.getUrl()))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("X-GPTR-Timestamp", timestamp)
                    .header("X-GPTR-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.getPayload()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            delivery.setLastStatus(response.statusCode());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                delivery.setStatus(WebhookStatus.SENT);
                delivery.setLastSuccessAt(OffsetDateTime.now());
                log.info("webhook delivered for task {} (status {})", delivery.getTaskId(), response.statusCode());
            } else {
                throw new IllegalStateException("webhook returned " + response.statusCode() + ": "
                        + response.body().substring(0, Math.min(ERROR_BODY_MAX_CHARS, response.body().length())));
            }
        } catch (Exception e) {
            delivery.setAttempts(delivery.getAttempts() + 1);
            delivery.setLastError(e.getMessage());
            if (delivery.getAttempts() >= maxAttempts) {
                delivery.setStatus(WebhookStatus.FAILED);
                log.warn("webhook delivery failed permanently for task {}: {}", delivery.getTaskId(), e.getMessage());
            } else {
                long backoff = (long) (backoffBaseMs * Math.pow(BACKOFF_MULTIPLIER, delivery.getAttempts() - 1));
                delivery.setNextAttemptAt(OffsetDateTime.now().plus(Duration.ofMillis(backoff)));
                log.debug("webhook delivery failed for task {} (attempt {}), retry in {}ms",
                        delivery.getTaskId(), delivery.getAttempts(), backoff);
            }
        }
        repository.save(delivery);
    }

    /** HMAC-SHA256 签名：data = timestamp + "." + payload。 */
    private String sign(String timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign webhook", e);
        }
    }
}
