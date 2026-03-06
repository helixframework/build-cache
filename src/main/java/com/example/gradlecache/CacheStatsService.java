package com.example.gradlecache;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

@Service
public class CacheStatsService {

    private final Instant startedAt = Instant.now();

    private final LongAdder requestCount = new LongAdder();
    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();
    private final LongAdder readBytes = new LongAdder();
    private final LongAdder writeBytes = new LongAdder();

    private final Map<Integer, LongAdder> statusCounts = new ConcurrentHashMap<>();
    private final Map<String, LatencyWindow> latencies = new ConcurrentHashMap<>();
    private final NavigableMap<Long, TrendBucket> hourlyBuckets = new ConcurrentSkipListMap<>();
    private final NavigableMap<Long, LongAdder> minuteRequestBuckets = new ConcurrentSkipListMap<>();

    public void recordPut(int statusCode, long writtenBytes, long durationNanos) {
        recordCommon("PUT", statusCode, durationNanos);
        if (writtenBytes > 0) {
            writeBytes.add(writtenBytes);
            hourBucket(Instant.now()).writeBytes.add(writtenBytes);
        }
    }

    public void recordGet(int statusCode, boolean hit, long payloadBytes, long durationNanos) {
        recordCommon("GET", statusCode, durationNanos);
        TrendBucket bucket = hourBucket(Instant.now());
        if (hit) {
            hitCount.increment();
            bucket.hits.increment();
            if (payloadBytes > 0) {
                readBytes.add(payloadBytes);
                bucket.readBytes.add(payloadBytes);
            }
        } else {
            missCount.increment();
            bucket.misses.increment();
        }
    }

    public void recordHead(int statusCode, boolean hit, long durationNanos) {
        recordCommon("HEAD", statusCode, durationNanos);
        TrendBucket bucket = hourBucket(Instant.now());
        if (hit) {
            hitCount.increment();
            bucket.hits.increment();
        } else {
            missCount.increment();
            bucket.misses.increment();
        }
    }

    public Map<String, Object> cacheTrendsSnapshot() {
        long hits = hitCount.sum();
        long misses = missCount.sum();
        long lookups = hits + misses;
        long read = readBytes.sum();
        long write = writeBytes.sum();

        List<Map<String, Object>> buckets = new ArrayList<>();
        Instant nowHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        for (int i = 23; i >= 0; i--) {
            Instant hour = nowHour.minus(i, ChronoUnit.HOURS);
            TrendBucket bucket = hourlyBuckets.get(hour.getEpochSecond());
            long bucketHits = bucket == null ? 0 : bucket.hits.sum();
            long bucketMisses = bucket == null ? 0 : bucket.misses.sum();
            long bucketLookups = bucketHits + bucketMisses;
            long bucketRead = bucket == null ? 0 : bucket.readBytes.sum();
            long bucketWrite = bucket == null ? 0 : bucket.writeBytes.sum();

            Map<String, Object> row = new HashMap<>();
            row.put("hourStart", hour);
            row.put("hitCount", bucketHits);
            row.put("missCount", bucketMisses);
            row.put("lookupCount", bucketLookups);
            row.put("hitRate", ratio(bucketHits, bucketLookups));
            row.put("readBytes", bucketRead);
            row.put("writeBytes", bucketWrite);
            row.put("estimatedBytesSaved", bucketRead);
            buckets.add(row);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("since", startedAt);
        out.put("hitCount", hits);
        out.put("missCount", misses);
        out.put("lookupCount", lookups);
        out.put("hitRate", ratio(hits, lookups));
        out.put("readBytes", read);
        out.put("writeBytes", write);
        out.put("estimatedBytesSaved", read);
        out.put("hourly", buckets);
        return out;
    }

    public Map<String, Object> performanceSnapshot() {
        long totalRequests = requestCount.sum();
        long uptimeSeconds = Math.max(1L, ChronoUnit.SECONDS.between(startedAt, Instant.now()));
        double avgRps = totalRequests / (double) uptimeSeconds;

        Instant nowMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        long recentRequests = 0;
        for (int i = 0; i < 5; i++) {
            LongAdder count = minuteRequestBuckets.get(nowMinute.minus(i, ChronoUnit.MINUTES).getEpochSecond());
            if (count != null) {
                recentRequests += count.sum();
            }
        }

        long totalErrors = statusCounts.entrySet().stream()
                .filter(e -> e.getKey() >= 400)
                .mapToLong(e -> e.getValue().sum())
                .sum();

        Map<String, Object> latencyMap = new HashMap<>();
        for (String method : List.of("GET", "PUT", "HEAD")) {
            LatencyWindow window = latencies.computeIfAbsent(method, ignored -> new LatencyWindow(10_000));
            latencyMap.put(method, window.snapshot());
        }

        Map<String, Long> statusMap = new HashMap<>();
        List<Integer> codes = new ArrayList<>(statusCounts.keySet());
        Collections.sort(codes);
        for (Integer code : codes) {
            statusMap.put(String.valueOf(code), statusCounts.get(code).sum());
        }

        Map<String, Object> out = new HashMap<>();
        out.put("since", startedAt);
        out.put("requestCount", totalRequests);
        out.put("requestRatePerSecond", round(avgRps));
        out.put("requestRateLast5MinutesPerSecond", round(recentRequests / 300.0));
        out.put("errorCount", totalErrors);
        out.put("errorRate", ratio(totalErrors, totalRequests));
        out.put("statusCodes", statusMap);
        out.put("latencyMs", latencyMap);
        return out;
    }

    private void recordCommon(String method, int statusCode, long durationNanos) {
        requestCount.increment();
        statusCounts.computeIfAbsent(statusCode, ignored -> new LongAdder()).increment();

        if (durationNanos > 0) {
            latencies.computeIfAbsent(method, ignored -> new LatencyWindow(10_000)).add(durationNanos);
        }

        Instant now = Instant.now();
        hourBucket(now).requests.increment();
        minuteBucket(now).increment();

        pruneOldBuckets(now);
    }

    private TrendBucket hourBucket(Instant instant) {
        long hour = instant.truncatedTo(ChronoUnit.HOURS).getEpochSecond();
        return hourlyBuckets.computeIfAbsent(hour, ignored -> new TrendBucket());
    }

    private LongAdder minuteBucket(Instant instant) {
        long minute = instant.truncatedTo(ChronoUnit.MINUTES).getEpochSecond();
        return minuteRequestBuckets.computeIfAbsent(minute, ignored -> new LongAdder());
    }

    private void pruneOldBuckets(Instant now) {
        long oldestHour = now.minus(48, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS).getEpochSecond();
        while (!hourlyBuckets.isEmpty() && hourlyBuckets.firstKey() < oldestHour) {
            hourlyBuckets.pollFirstEntry();
        }

        long oldestMinute = now.minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES).getEpochSecond();
        while (!minuteRequestBuckets.isEmpty() && minuteRequestBuckets.firstKey() < oldestMinute) {
            minuteRequestBuckets.pollFirstEntry();
        }
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return round(numerator / (double) denominator);
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static final class TrendBucket {
        private final LongAdder requests = new LongAdder();
        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();
        private final LongAdder readBytes = new LongAdder();
        private final LongAdder writeBytes = new LongAdder();
    }

    private static final class LatencyWindow {
        private final int maxSamples;
        private final ArrayDeque<Long> samples = new ArrayDeque<>();

        private LatencyWindow(int maxSamples) {
            this.maxSamples = maxSamples;
        }

        private synchronized void add(long durationNanos) {
            if (samples.size() >= maxSamples) {
                samples.removeFirst();
            }
            samples.addLast(durationNanos);
        }

        private synchronized Map<String, Object> snapshot() {
            int size = samples.size();
            if (size == 0) {
                return Map.of(
                        "samples", 0,
                        "p50", 0.0,
                        "p95", 0.0,
                        "p99", 0.0);
            }

            List<Long> sorted = new ArrayList<>(samples);
            sorted.sort(Comparator.naturalOrder());

            return Map.of(
                    "samples", size,
                    "p50", nanosToMillis(percentile(sorted, 0.50)),
                    "p95", nanosToMillis(percentile(sorted, 0.95)),
                    "p99", nanosToMillis(percentile(sorted, 0.99)));
        }

        private static long percentile(List<Long> sorted, double percentile) {
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            return sorted.get(index);
        }

        private static double nanosToMillis(long nanos) {
            return Math.round((nanos / 1_000_000.0) * 1000.0) / 1000.0;
        }
    }
}
