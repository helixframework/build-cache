package com.example.gradlecache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class FileBackedCacheService {

    private static final Pattern CACHE_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{16,256}$");

    private final Path cacheRoot;

    public FileBackedCacheService(CacheProperties properties) throws IOException {
        this.cacheRoot = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
        Files.createDirectories(cacheRoot);
    }

    public Optional<Path> find(String key) {
        Path path = resolvePath(key);
        return Files.exists(path) ? Optional.of(path) : Optional.empty();
    }

    public boolean put(String key, InputStream inputStream) throws IOException {
        Path path = resolvePath(key);
        Files.createDirectories(path.getParent());

        Path tempFile = Files.createTempFile(path.getParent(), key + ".", ".tmp");
        try (inputStream) {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        boolean existed = Files.exists(path);
        try {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
        }

        return !existed;
    }

    private Path resolvePath(String key) {
        if (!CACHE_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid cache key");
        }

        String prefix = key.substring(0, 2);
        Path path = cacheRoot.resolve(prefix).resolve(key).normalize();
        if (!path.startsWith(cacheRoot)) {
            throw new IllegalArgumentException("Invalid cache key path");
        }

        return path;
    }
}
