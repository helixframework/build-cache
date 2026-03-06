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
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    public BuildCacheController(FileBackedCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping(path = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }

    @PutMapping(path = "/cache/{key}")
    public ResponseEntity<Void> store(@PathVariable String key, HttpServletRequest request) throws IOException {
        boolean created = cacheService.put(key, request.getInputStream());
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }

    @GetMapping(path = "/cache/{key}")
    public ResponseEntity<FileSystemResource> load(@PathVariable String key) throws IOException {
        Optional<Path> cached = cacheService.find(key);
        if (cached.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Path path = cached.get();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(path))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .body(new FileSystemResource(path));
    }

    @RequestMapping(path = "/cache/{key}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable String key) throws IOException {
        Optional<Path> cached = cacheService.find(key);
        if (cached.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentLength(Files.size(cached.get()))
                .build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> invalidKey() {
        return ResponseEntity.badRequest().build();
    }
}
