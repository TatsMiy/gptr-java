package com.gptr.benchmark.report;


import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 结果落盘：per-item.jsonl（逐条明细）+ metrics.json（聚合）。 */
public final class ResultWriter {

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    public ResultWriter(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    public Path dir() {
        return dir;
    }

    /** 追加一条明细。 */
    public void appendItem(ObjectNode item) throws IOException {
        Files.writeString(dir.resolve("per-item.jsonl"),
                mapper.writeValueAsString(item) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** 写聚合指标。 */
    public void writeMetrics(ObjectNode metrics) throws IOException {
        Files.writeString(dir.resolve("metrics.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics),
                StandardCharsets.UTF_8);
    }

    public static ObjectNode obj(ObjectMapper m) {
        return m.createObjectNode();
    }

    public static ArrayNode arr(ObjectMapper m) {
        return m.createArrayNode();
    }
}
