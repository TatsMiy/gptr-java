package com.gptr.common.repository;

import com.gptr.common.task.WebhookDelivery;
import com.gptr.common.task.WebhookStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

/** Webhook 投递仓库。 */
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    /** 待投递（PENDING 且到时间）的记录，供投递器扫描。 */
    List<WebhookDelivery> findByStatusAndNextAttemptAtBefore(WebhookStatus status, OffsetDateTime time);
}
