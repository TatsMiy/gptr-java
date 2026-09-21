package com.gptr.engine.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
  * 配置决定点登记的机械判据。
 *
 * <p>本批只落地**判据 1**（锚 A 完整性）；判据 2-5 待锚 B 的范围确定后补。
 */
class ConfigKeyRegistryTest {

    /** 锚 A 的宿主类：包级私有，故只能经 {@code Class.forName} 反射进入。 */
    private static final String ENGINE_CONFIG = "com.gptr.engine.EngineConfig";

    /** 判据 1：锚 A（全部实例字段）必须**逐个**带 {@code @ConfigKey}，且只带一种标记。 */
    @Test
    void engineConfigFieldsAreAllMarked() throws Exception {
        Class<?> type = Class.forName(ENGINE_CONFIG);
        List<String> unmarked = new ArrayList<>();
        List<String> doubleMarked = new ArrayList<>();
        int instanceFields = 0;
        int marked = 0;
        for (Field field : type.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (Modifier.isStatic(mod) || field.isSynthetic()) {
                continue;
            }
            instanceFields++;
            boolean hasKey = field.isAnnotationPresent(ConfigKey.class);
            boolean hasExemption = field.isAnnotationPresent(NotADecision.class);
            if (hasKey) {
                marked++;
            } else {
                unmarked.add(field.getName());
            }
            if (hasKey && hasExemption) {
                doubleMarked.add(field.getName());
            }
        }
        assertTrue(instanceFields > 0, "锚 A 一个实例字段都没数到——反射目标类或过滤条件已失效（假绿保护）");
        assertEquals(instanceFields, marked,
                "锚 A 有未登记的决定点：字段 " + instanceFields + " 个 / @ConfigKey " + marked + " 个；未标记 "
                        + unmarked);
        assertEquals(List.of(), doubleMarked, "每个受管对象必须带且只带一种标记（@ConfigKey 或 @NotADecision）");
    }
}
