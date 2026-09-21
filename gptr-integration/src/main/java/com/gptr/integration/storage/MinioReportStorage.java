package com.gptr.integration.storage;

import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.MinioException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * MinIO（S3 兼容）实现：ref = {@code minio://<bucket>/<key>}。
 *
 * <p>启动时确保 bucket 存在（bucketExists 否则 makeBucket）。
 */
public class MinioReportStorage implements ReportStorage {

    private final MinioClient client;
    private final String bucket;

    public MinioReportStorage(String endpoint, String accessKey, String secretKey, String bucket) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            boolean exists = client.bucketExists(io.minio.BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (MinioException | java.security.InvalidKeyException | java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("failed to ensure MinIO bucket " + bucket, e);
        }
    }

    private String key(UUID taskId) {
        return "tasks/" + taskId + "/report.md";
    }

    @Override
    public String put(UUID taskId, String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key(taskId))
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("text/markdown")
                    .build());
            return "minio://" + bucket + "/" + key(taskId);
        } catch (Exception e) {
            throw new IllegalStateException("failed to upload report for task " + taskId, e);
        }
    }

    @Override
    public String get(String ref) {
        try {
            String key = ref.substring(ref.indexOf('/', ref.indexOf("//") + 2) + 1);
            try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to read report " + ref, e);
        }
    }

    @Override
    public void delete(String ref) {
        try {
            String key = ref.substring(ref.indexOf('/', ref.indexOf("//") + 2) + 1);
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new IllegalStateException("failed to delete report " + ref, e);
        }
    }

    /** 供测试/运维检查对象是否存在。 */
    public boolean exists(String ref) {
        try {
            String key = ref.substring(ref.indexOf('/', ref.indexOf("//") + 2) + 1);
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
