package com.gptr.api;

import com.gptr.integration.storage.LocalReportStorage;
import com.gptr.integration.storage.MinioReportStorage;
import com.gptr.integration.storage.ReportStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * OBS-2：api 侧报告存储装配（读最终研报正文用）。
 *
 * <p>与 worker 侧同键同默认（{@code gptr.storage.*}），保证 api/worker 读到同一产物；
 * worker 写、api 读，ref 为绝对路径/桶定位，跨进程一致。
 */
@Configuration
public class ApiReportStorageConfig {

    @Bean
    public ReportStorage apiReportStorage(
            @Value("${gptr.storage.type:local}") String type,
            @Value("${gptr.storage.local-dir:./outputs}") String localDir,
            @Value("${gptr.storage.minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${gptr.storage.minio.access-key:gptr}") String accessKey,
            @Value("${gptr.storage.minio.secret-key:gptr12345}") String secretKey,
            @Value("${gptr.storage.minio.bucket:gptr-reports}") String bucket) {
        return switch (type.toLowerCase()) {
            case "minio" -> new MinioReportStorage(endpoint, accessKey, secretKey, bucket);
            case "local" -> new LocalReportStorage(Path.of(localDir));
            default -> throw new IllegalArgumentException("unknown gptr.storage.type: " + type);
        };
    }
}
