package com.gptr.api.config;

import com.gptr.api.dto.AppConfigView;
import com.gptr.common.config.AppConfigEntry;
import com.gptr.common.repository.AppConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 全局配置服务：白名单 CRUD + secret 不读明文。
 *
 * <p>语义：配置保存立即落库，**worker 重启后生效**；
 * api 不做热替换、不推在跑任务。secret 行写入后即不可读回（只可覆盖/删除）。
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final AppConfigRepository repository;

    @Transactional(readOnly = true)
    public List<AppConfigView> list() {
        return ConfigKeyMeta.ALL.stream().map(meta -> {
            AppConfigEntry row = repository.findById(meta.key()).orElse(null);
            if (row == null) {
                return new AppConfigView(meta.key(), meta.description(), meta.secret(),
                        false, null, 0, null);
            }
            return new AppConfigView(meta.key(), meta.description(), meta.secret(),
                    true, meta.secret() ? null : row.getValue(),
                    row.getVersion(), row.getUpdatedAt());
        }).toList();
    }

    /** 保存覆盖值；白名单外键或值非法 → IllegalArgumentException（controller 转 400）。 */
    @Transactional
    public AppConfigView save(String key, String value) {
        ConfigKeyMeta meta = ConfigKeyMeta.byKey(key)
                .orElseThrow(() -> new IllegalArgumentException("unknown config key: " + key));
        if (!meta.validator().test(value)) {
            throw new IllegalArgumentException("invalid value for " + key);
        }
        AppConfigEntry row = repository.findById(key).orElseGet(() -> {
            AppConfigEntry fresh = new AppConfigEntry();
            fresh.setKey(key);
            fresh.setSecret(meta.secret());
            fresh.setVersion(1); // 首次覆盖即版本 1（后续脏写由 @PreUpdate +1）
            return fresh;
        });
        row.setValue(value.trim());
        AppConfigEntry saved = repository.save(row); // 脏写触发 @PreUpdate version+1
        return new AppConfigView(meta.key(), meta.description(), meta.secret(), true,
                meta.secret() ? null : saved.getValue(), saved.getVersion(), saved.getUpdatedAt());
    }

    /** 删除覆盖行 → 恢复 worker 内置默认。 */
    @Transactional
    public void delete(String key) {
        ConfigKeyMeta.byKey(key)
                .orElseThrow(() -> new IllegalArgumentException("unknown config key: " + key));
        repository.deleteById(key);
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now();
    }
}
