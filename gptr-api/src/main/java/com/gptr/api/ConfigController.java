package com.gptr.api;

import com.gptr.api.config.ConfigService;
import com.gptr.api.dto.AppConfigView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 全局运行配置 API（保存后重启 worker 生效；无热加载）。
 *
 * <pre>
 * GET    /api/v1/config          配置清单（secret 不回显明文）
 * PUT    /api/v1/config/{key}    body {"value": "..."} 覆盖（白名单校验）
 * DELETE /api/v1/config/{key}    删除覆盖行 → 恢复 worker 内置默认
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    @GetMapping
    public List<AppConfigView> list() {
        return configService.list();
    }

    /** 配置覆盖请求体。 */
    public record UpdateConfigRequest(String value) {
    }

    @PutMapping("/{key}")
    public AppConfigView save(@PathVariable String key, @RequestBody UpdateConfigRequest req) {
        return configService.save(key, req == null ? null : req.value());
    }

    @DeleteMapping("/{key}")
    public void delete(@PathVariable String key) {
        configService.delete(key);
    }

    /** 白名单外键 / 非法值 → 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }
}
