package com.gptr.engine.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式豁免：声明"这个 {@code static final} 数值常量**不是**决定点"。
 *
 * <p>豁免**理由必填**——否则"常量数 == 标记数"这条判据会被无意义的豁免批量满足。
 *
 * @implNote [锚 B 完整性] 数值常量必须二选一：本注解或 {@link ConfigKey}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface NotADecision {

    /** 为什么它不是决定点（如批量大小、日志截断长度、重试次数、格式串）。 */
    String reason();
}
