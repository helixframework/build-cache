# Gradle Build Cache Server

This project provides a self-hosted remote Gradle build cache using Spring Boot.

It implements the HTTP endpoints needed by Gradle remote build cache:

- `PUT /cache/{key}`: store cache entry
- `GET /cache/{key}`: load cache entry
- `HEAD /cache/{key}`: check cache entry existence/size

Cache objects are persisted on disk under `/data/cache` in the container.

## Stats APIs

The server exposes dashboard-ready JSON endpoints:

- `GET /api/stats/cache-trends`: hit/miss trends, read/write volume, estimated bytes saved (hourly + overall)
- `GET /api/stats/keyspace`: entry count, total size, oldest/newest artifacts, top namespaces/projects
- `GET /api/stats/performance`: request rate, p50/p95/p99 latency for `GET`/`PUT`/`HEAD`, status code error data

Optional request headers on `PUT /cache/{key}` for namespace/project attribution:

- `X-Cache-Namespace`
- `X-Cache-Project`

## Run with Docker Compose

```bash
docker compose up --build -d
```

The server listens on:

- `http://localhost:5071/cache/`

Cache data is persisted on your host in `./cache-data`.

## Use in your Gradle builds

Add this to `settings.gradle` (or `settings.gradle.kts`) in each project that should use this cache.

### Groovy DSL (`settings.gradle`)

```groovy
buildCache {
    local {
        enabled = true
    }
    remote(HttpBuildCache) {
        url = 'http://localhost:5071/cache/'
        push = true
        allowInsecureProtocol = true
    }
}
```

### Kotlin DSL (`settings.gradle.kts`)

```kotlin
buildCache {
    local {
        isEnabled = true
    }
    remote<HttpBuildCache> {
        url = uri("http://localhost:5071/cache/")
        isPush = true
        isAllowInsecureProtocol = true
    }
}
```

Then run builds with cache enabled:

```bash
./gradlew build --build-cache
```

## Optional configuration

Set environment variable to change cache storage path:

- `GRADLE_CACHE_STORAGE_DIR=/data/cache`

The default server port is `5071`.

## Troubleshooting TLS

If you see this error during a Gradle build:

`The remote build cache was disabled during the build due to errors.`

and you are fronting this cache with Caddy/TLS, add your Caddy certificate (or issuing CA certificate) to the JVM truststore used by Gradle.

Example:

```bash
keytool -importcert \
  -alias caddy-local-ca \
  -file /path/to/caddy-ca.crt \
  -keystore "$JAVA_HOME/lib/security/cacerts" \
  -storepass changeit
```

Then rerun your Gradle build.

## Security note

This starter service has no authentication and is intended for trusted networks.
For shared/untrusted networks, place it behind TLS and authentication (for example, reverse proxy with basic auth or mTLS).
