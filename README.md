# Throttle

![CI](https://github.com/sanikasurose/throttle/actions/workflows/ci.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.12-green)
![Redis](https://img.shields.io/badge/Redis-7-red)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=sanikasurose_throttle&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=sanikasurose_throttle)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=sanikasurose_throttle&metric=coverage)](https://sonarcloud.io/summary/new_code?id=sanikasurose_throttle)

A production-grade API rate limiting service built in Java 17 and 
Spring Boot. Throttle enforces per-user request quotas using a sliding 
window algorithm backed by Redis, with atomic Lua script execution and 
a real-time metrics endpoint.

## Tech stack

Java 17 · Spring Boot 3.5.12 · Redis 7 · Docker · JUnit 5 · Maven

## How it works

Each incoming request carries a `X-User-Id` header. Throttle checks 
whether that user has exceeded their configured request quota within 
the current sliding window. If yes, it returns HTTP 429. If no, it 
allows the request and records the timestamp atomically in Redis.

The sliding window algorithm prevents the boundary exploit present in 
fixed window implementations. The evict → count → add sequence runs 
as a single Lua script on Redis, making it safe under concurrent load 
and across multiple service instances.

## Running locally

**Prerequisites:** Java 17, Docker Desktop
```bash
# Start Redis and the app together
docker compose up --build

# Or run just Redis and use Maven for the app (faster for development)
docker compose up redis -d
./mvnw spring-boot:run
```

The API is available at `http://localhost:8080`.

## Testing the rate limiter
```bash
# Fire 7 requests — first 5 return 200, last 2 return 429
for i in {1..7}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "X-User-Id: sanika" \
    http://localhost:8080/ping
done

# Check per-user metrics
curl http://localhost:8080/metrics
```

## Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/ping` | Test endpoint — returns 200 or 429 |
| GET | `/metrics` | Per-user request and rejection stats |
| GET | `/actuator/health` | Service health check |

## Running tests
```bash
mvn test
```

## Project structure
```
src/main/java/com/sanikasurose/throttle/
├── core/        # Rate limiting logic — no Spring dependencies
│   ├── Clock.java
│   ├── SystemClock.java
│   ├── RateLimitPolicy.java
│   ├── RequestRecord.java
│   └── RateLimiter.java
├── metrics/     # Per-user stats tracking
│   └── RateLimiterMetrics.java
└── web/         # Spring filter and REST controllers
    ├── ThrottleConfig.java
    ├── RateLimitFilter.java
    └── MetricsController.java
```

## Design decisions

See [DESIGN.md](docs/DESIGN.md) for full reasoning behind every 
architectural decision.
