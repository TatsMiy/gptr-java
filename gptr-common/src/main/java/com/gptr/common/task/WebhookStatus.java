package com.gptr.common.task;

/** Webhook 投递状态。 */
public enum WebhookStatus {

    /** 待投递（含退避等待重试） */
    PENDING,

    /** 已成功送达 */
    SENT,

    /** 重试超限放弃 */
    FAILED
}
