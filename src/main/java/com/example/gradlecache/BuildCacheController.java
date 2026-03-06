package com.example.gradlecache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class BuildCacheController {

    private final FileBackedCacheService cacheService;
    private final CacheStatsService statsService;

    public BuildCacheController(FileBackedCacheService cacheService, CacheStatsService statsService) {
        this.cacheService = cacheService;
        this.statsService = statsService;
    }

    @GetMapping(path = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }

    @PutMapping(path = "/cache/{key}")
    public ResponseEntity<Void> store(@PathVariable String key, HttpServletRequest request) throws IOException {
        long start = System.nanoTime();
        try {
            String namespace = scopeHeader(request, "X-Cache-Namespace");
            String project = scopeHeader(request, "X-Cache-Project");
            FileBackedCacheService.CacheStoreResult result = cacheService.put(key, request.getInputStream(), namespace, project);

            HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
            statsService.recordPut(status.value(), result.sizeBytes(), System.nanoTime() - start);
            return ResponseEntity.status(status).build();
        } catch (IllegalArgumentException ex) {
            statsService.recordPut(HttpStatus.BAD_REQUEST.value(), 0, System.nanoTime() - start);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping(path = "/cache/{key}")
    public ResponseEntity<FileSystemResource> load(@PathVariable String key) throws IOException {
        long start = System.nanoTime();
        try {
            Optional<Path> cached = cacheService.find(key);
            if (cached.isEmpty()) {
                statsService.recordGet(HttpStatus.NOT_FOUND.value(), false, 0, System.nanoTime() - start);
                return ResponseEntity.notFound().build();
            }

            Path path = cached.get();
            long bytes = Files.size(path);
            statsService.recordGet(HttpStatus.OK.value(), true, bytes, System.nanoTime() - start);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(bytes)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                    .body(new FileSystemResource(path));
        } catch (IllegalArgumentException ex) {
            statsService.recordGet(HttpStatus.BAD_REQUEST.value(), false, 0, System.nanoTime() - start);
            return ResponseEntity.badRequest().build();
        }
    }

    @RequestMapping(path = "/cache/{key}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable String key) throws IOException {
        long start = System.nanoTime();
        try {
            Optional<Path> cached = cacheService.find(key);
            if (cached.isEmpty()) {
                statsService.recordHead(HttpStatus.NOT_FOUND.value(), false, System.nanoTime() - start);
                return ResponseEntity.notFound().build();
            }

            long bytes = Files.size(cached.get());
            statsService.recordHead(HttpStatus.OK.value(), true, System.nanoTime() - start);
            return ResponseEntity.ok()
                    .contentLength(bytes)
                    .build();
        } catch (IllegalArgumentException ex) {
            statsService.recordHead(HttpStatus.BAD_REQUEST.value(), false, System.nanoTime() - start);
            return ResponseEntity.badRequest().build();
        }
    }

    private static String scopeHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
