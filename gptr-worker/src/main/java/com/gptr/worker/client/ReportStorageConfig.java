package com.gptr.worker.client;

import com.gptr.integration.storage.LocalReportStorage;
import com.gptr.integration.storage.MinioReportStorage;
import com.gptr.integration.storage.ReportStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 报告存储装配：{@code gptr.storage.type=minio|local}。
 */
@Configuration
public class ReportStorageConfig {

    @Bean
    public ReportStorage reportStorage(
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
