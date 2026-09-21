package com.gptr.integration.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 本地磁盘实现（默认，方便本地开发）：ref = {@code local://<abs path>}。
 */
public class LocalReportStorage implements ReportStorage {

    private final Path rootDir;

    public LocalReportStorage(Path rootDir) {
        this.rootDir = rootDir;
    }

    @Override
    public String put(UUID taskId, String content) {
        try {
            Files.createDirectories(rootDir);
            Path file = rootDir.resolve(taskId + ".md");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return "local://" + file.toAbsolutePath();
        } catch (IOException e) {
            throw new IllegalStateException("failed to write report for task " + taskId, e);
        }
    }

    @Override
    public String get(String ref) {
        try {
            return Files.readString(Path.of(ref.substring("local://".length())), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read report " + ref, e);
        }
    }

    @Override
    public void delete(String ref) {
        try {
            Files.deleteIfExists(Path.of(ref.substring("local://".length())));
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete report " + ref, e);
        }
    }
}
