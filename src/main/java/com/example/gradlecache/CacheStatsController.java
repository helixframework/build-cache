package com.example.gradlecache;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/stats")
public class CacheStatsController {

    private final CacheStatsService statsService;
    private final FileBackedCacheService cacheService;

    public CacheStatsController(CacheStatsService statsService, FileBackedCacheService cacheService) {
        this.statsService = statsService;
        this.cacheService = cacheService;
    }

    @GetMapping(path = "/cache-trends")
    public ResponseEntity<Map<String, Object>> cacheTrends() {
        return ResponseEntity.ok(statsService.cacheTrendsSnapshot());
    }

    @GetMapping(path = "/keyspace")
    public ResponseEntity<Map<String, Object>> keyspace() throws IOException {
        return ResponseEntity.ok(cacheService.keyspaceStats());
    }

    @GetMapping(path = "/performance")
    public ResponseEntity<Map<String, Object>> performance() {
        return ResponseEntity.ok(statsService.performanceSnapshot());
    }
}
