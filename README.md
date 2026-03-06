# Gradle Build Cache Server (Spring Boot + Docker)

This project provides a self-hosted remote Gradle build cache using Spring Boot.

It implements the HTTP endpoints needed by Gradle remote build cache:

- `PUT /cache/{key}`: store cache entry
- `GET /cache/{key}`: load cache entry
- `HEAD /cache/{key}`: check cache entry existence/size

Cache objects are persisted on disk under `/data/cache` in the container.

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

## Security note

This starter service has no authentication and is intended for trusted networks.
For shared/untrusted networks, place it behind TLS and authentication (for example, reverse proxy with basic auth or mTLS).
