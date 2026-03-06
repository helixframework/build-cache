package com.example.gradlecache;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BuildCacheControllerTest {

    private static Path tempDir;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void createTempDir() throws Exception {
        tempDir = Files.createTempDirectory("gradle-build-cache-test");
    }

    @AfterAll
    static void cleanupTempDir() throws Exception {
        if (tempDir != null) {
            try (Stream<Path> paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("gradle.cache.storage-dir", () -> tempDir.toString());
    }

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void storesAndLoadsCacheEntry() throws Exception {
        String key = "1234567890abcdef1234567890abcdef";
        byte[] payload = "compiled-output".getBytes();

        mockMvc.perform(put("/cache/{key}", key).content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/cache/{key}", key))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(content().bytes(payload));

        mockMvc.perform(head("/cache/{key}", key))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", String.valueOf(payload.length)));
    }

    @Test
    void returnsNotFoundForUnknownKey() throws Exception {
        mockMvc.perform(get("/cache/{key}", "abcdefabcdefabcdefabcdefabcdefab"))
                .andExpect(status().isNotFound());
    }

    @Test
    void statsEndpointsExposeMetrics() throws Exception {
        String key = "fedcba0987654321fedcba0987654321";
        byte[] payload = "metrics-payload".getBytes();

        mockMvc.perform(put("/cache/{key}", key)
                        .header("X-Cache-Namespace", "team-alpha")
                        .header("X-Cache-Project", "service-a")
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/cache/{key}", key))
                .andExpect(status().isOk());

        mockMvc.perform(get("/cache/{key}", "00000000000000000000000000000000"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/stats/cache-trends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hitCount").isNumber())
                .andExpect(jsonPath("$.missCount").isNumber())
                .andExpect(jsonPath("$.readBytes").isNumber())
                .andExpect(jsonPath("$.writeBytes").isNumber())
                .andExpect(jsonPath("$.hourly").isArray());

        mockMvc.perform(get("/api/stats/keyspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryCount").isNumber())
                .andExpect(jsonPath("$.totalSizeBytes").isNumber())
                .andExpect(jsonPath("$.topNamespaces").isArray())
                .andExpect(jsonPath("$.topProjects").isArray());

        mockMvc.perform(get("/api/stats/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestCount").isNumber())
                .andExpect(jsonPath("$.latencyMs.GET").exists())
                .andExpect(jsonPath("$.statusCodes").isMap());
    }
}
