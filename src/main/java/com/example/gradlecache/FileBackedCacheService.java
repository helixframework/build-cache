package com.example.gradlecache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    public CacheStoreResult put(String key, InputStream inputStream, String namespace, String project) throws IOException {
        Path path = resolvePath(key);
        Files.createDirectories(path.getParent());

        Path tempFile = Files.createTempFile(path.getParent(), key + ".", ".tmp");
        long bytes;
        try (inputStream) {
            bytes = Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        boolean existed = Files.exists(path);
        try {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
        }

        writeMetadata(path, normalizeScope(namespace), normalizeScope(project));
        return new CacheStoreResult(!existed, bytes);
    }

    public Map<String, Object> keyspaceStats() throws IOException {
        long entryCount = 0;
        long totalSizeBytes = 0;
        Instant oldestArtifactAt = null;
        Instant newestArtifactAt = null;

        Map<String, ScopeAggregate> namespaceAgg = new HashMap<>();
        Map<String, ScopeAggregate> projectAgg = new HashMap<>();

        try (Stream<Path> paths = Files.walk(cacheRoot)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(path) || isMetadataOrTemp(path)) {
                    continue;
                }

                long size = Files.size(path);
                FileTime fileTime = Files.getLastModifiedTime(path);
                Instant modified = fileTime.toInstant();

                entryCount++;
                totalSizeBytes += size;
                oldestArtifactAt = oldestArtifactAt == null || modified.isBefore(oldestArtifactAt) ? modified : oldestArtifactAt;
                newestArtifactAt = newestArtifactAt == null || modified.isAfter(newestArtifactAt) ? modified : newestArtifactAt;

                Map<String, String> metadata = readMetadata(path);
                String namespace = metadata.getOrDefault("namespace", "unknown");
                String project = metadata.getOrDefault("project", "unknown");

                namespaceAgg.computeIfAbsent(namespace, ignored -> new ScopeAggregate()).add(size);
                projectAgg.computeIfAbsent(project, ignored -> new ScopeAggregate()).add(size);
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("entryCount", entryCount);
        out.put("totalSizeBytes", totalSizeBytes);
        out.put("oldestArtifactAt", oldestArtifactAt);
        out.put("newestArtifactAt", newestArtifactAt);
        out.put("topNamespaces", topScopes(namespaceAgg));
        out.put("topProjects", topScopes(projectAgg));
        return out;
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

    private static boolean isMetadataOrTemp(Path path) {
        String file = path.getFileName().toString();
        return file.endsWith(".meta") || file.endsWith(".tmp");
    }

    private static String normalizeScope(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace("\n", " ").replace("\r", " ").trim();
    }

    private static List<Map<String, Object>> topScopes(Map<String, ScopeAggregate> aggregate) {
        List<Map.Entry<String, ScopeAggregate>> entries = new ArrayList<>(aggregate.entrySet());
        entries.sort((left, right) -> Long.compare(right.getValue().totalSizeBytes, left.getValue().totalSizeBytes));

        List<Map<String, Object>> top = new ArrayList<>();
        int max = Math.min(10, entries.size());
        for (int i = 0; i < max; i++) {
            Map.Entry<String, ScopeAggregate> entry = entries.get(i);
            Map<String, Object> row = new HashMap<>();
            row.put("name", entry.getKey());
            row.put("entryCount", entry.getValue().entryCount);
            row.put("totalSizeBytes", entry.getValue().totalSizeBytes);
            top.add(row);
        }
        return top;
    }

    private static Map<String, String> readMetadata(Path artifactPath) {
        Path metadataPath = metadataPath(artifactPath);
        if (!Files.exists(metadataPath)) {
            return Map.of();
        }

        Map<String, String> out = new HashMap<>();
        try {
            for (String line : Files.readAllLines(metadataPath, StandardCharsets.UTF_8)) {
                int idx = line.indexOf('=');
                if (idx <= 0 || idx >= line.length() - 1) {
                    continue;
                }
                out.put(line.substring(0, idx), line.substring(idx + 1));
            }
        } catch (IOException ignored) {
            return Map.of();
        }
        return out;
    }

    private static void writeMetadata(Path artifactPath, String namespace, String project) throws IOException {
        Path metadataPath = metadataPath(artifactPath);
        String content = "namespace=" + namespace + "\nproject=" + project + "\n";

        Path temp = Files.createTempFile(metadataPath.getParent(), metadataPath.getFileName().toString(), ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, metadataPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, metadataPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path metadataPath(Path artifactPath) {
        return artifactPath.resolveSibling(artifactPath.getFileName().toString() + ".meta");
    }

    public record CacheStoreResult(boolean created, long sizeBytes) {
    }

    private static final class ScopeAggregate {
        private long entryCount;
        private long totalSizeBytes;

        private void add(long sizeBytes) {
            entryCount++;
            totalSizeBytes += sizeBytes;
        }
    }
}
