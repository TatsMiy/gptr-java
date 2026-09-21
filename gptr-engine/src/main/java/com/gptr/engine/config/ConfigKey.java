package com.gptr.engine.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 配置决定点的登记标记：**每个承载 config 值的字段必须带它**，缺失即测试失败。
 *
 * <p>登记写在字段上（注解），不另建独立登记文件——避免两份清单不同步。
 * 标记只用于登记与测试：**配置读取路径不得依赖它**（禁止把反射带进热路径）。
 *
 * @implNote [锚 A 完整性] 反射数 {@code EngineConfig} 的全部实例字段，
 *           字段数必须等于本注解数；判据见 {@code ConfigKeyRegistryTest}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface ConfigKey {

    /** 类别：决定"该活多久、谁负责"。EXPERIMENT 必须有过期日。 */
    Kind kind();

    /** 负责人（人，不是代号）。全部类别必填。 */
    String owner();

    /** 引入日期，ISO {@code yyyy-MM-dd}。全部类别必填。 */
    String added();

    /** 过期日 ISO {@code yyyy-MM-dd}。EXPERIMENT 必填；UNVERIFIED 建议填；OPS/STABLE 不填。 */
    String expires() default "";

    /** 实验状态。仅 EXPERIMENT 有意义：OPEN = 未结案。 */
    State state() default State.NOT_APPLICABLE;

    /** 验收/依据的文档路径（结案或已有记录时必填）。 */
    String evidence() default "";

    /** 为什么不需要过期（STABLE/OPS 时的可选说明；UNVERIFIED 应说明缺什么证据）。 */
    String reason() default "";

    /** 类别：决定"该活多久、谁负责"。 */
    enum Kind { STABLE, EXPERIMENT, OPS, UNVERIFIED }

    /** 实验状态：OPEN = 未结案。 */
    enum State { NOT_APPLICABLE, OPEN, CLOSED_FLIPPED, CLOSED_PROFILED, CLOSED_REMOVED }
}
