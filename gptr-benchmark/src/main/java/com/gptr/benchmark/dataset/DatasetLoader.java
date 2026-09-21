package com.gptr.benchmark.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 题集加载器：逐行 JSON（id/query/mode/lang/gold?）。源可为 classpath（默认
 * datasets/benchmark.jsonl）或文件路径（--dataset 覆盖）。
 */
public final class DatasetLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DatasetLoader() {
    }

    public static List<BenchmarkItem> load(String location) {
        List<BenchmarkItem> items = new ArrayList<>();
        try {
            BufferedReader reader;
            if (location.startsWith("classpath:")) {
                String res = location.substring("classpath:".length());
                InputStream in = DatasetLoader.class.getClassLoader().getResourceAsStream(res);
                if (in == null) {
                    throw new IllegalArgumentException("dataset not found on classpath: " + res);
                }
                reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            } else {
                reader = Files.newBufferedReader(Path.of(location), StandardCharsets.UTF_8);
            }
            try (reader) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = MAPPER.readTree(line);
                    String gold = node.path("gold").isTextual() ? node.path("gold").asText() : null;
                    String superseded = node.path("superseded").isTextual()
                            ? node.path("superseded").asText() : null;
                    List<String> blocked = new ArrayList<>();
                    if (node.path("blocked").isArray()) {
                        for (JsonNode b : node.path("blocked")) {
                            if (b.isTextual() && !b.asText().isBlank()) {
                                blocked.add(b.asText().trim());
                            }
                        }
                    }
                    items.add(new BenchmarkItem(
                            node.path("id").asText(),
                            node.path("query").asText(),
                            node.path("mode").asText("flat"),
                            node.path("lang").asText("zh"),
                            node.path("cat").asText("general"),
                            superseded,
                            gold,
                            blocked));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to load dataset: " + location, e);
        }
        return items;
    }
}
