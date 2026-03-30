# Throttle

![CI](https://github.com/sanikasurose/throttle/actions/workflows/ci.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.12-green)
![Redis](https://img.shields.io/badge/Redis-7-red)

Production-grade API rate limiting service built with Java 17 and Spring Boot.
Throttle enforces per-user request quotas using a **sliding window** algorithm
backed by **Redis**, with atomic **Lua script** execution and a lightweight
**per-user metrics** endpoint.

## Features

- Sliding-window throttling (avoids fixed-window boundary exploits)
- Redis-backed and safe under concurrency (atomic Lua script per request)
- Header-based identity via `X-User-Id`
- `/metrics` endpoint with per-user allow/reject counters
- Docker Compose for local development (Redis + service)
- Spring Boot Actuator health endpoint

## Tech stack

Java 17 · Spring Boot 3.5.12 · Redis 7 · Docker · Maven · JUnit 5

## Installation

**Prerequisites:** Java 17 and Docker Desktop (for Redis)

```bash
# Clone
git clone https://github.com/sanikasurose/throttle.git
cd throttle

# Build
./mvnw clean package
```

## Usage

### Run locally

```bash
# Start Redis and the app together
docker compose up --build

# Or: run Redis only and start the app with Maven (faster for development)
docker compose up redis -d
./mvnw spring-boot:run
```

The API is available at `http://localhost:8080`.

### Try the rate limiter

```bash
# Fire 7 requests — first 5 return 200, last 2 return 429 (default policy)
for i in {1..7}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "X-User-Id: sanika" \
    http://localhost:8080/ping
done

# Per-user metrics snapshot
curl http://localhost:8080/metrics
```

## Configuration

- **User identity:** every request must include the `X-User-Id` header (missing/blank → `400`).
- **Default policy:** `5` requests per `10` seconds (configured in `ThrottleConfig.rateLimitPolicy()`).
- **Redis connection:** set `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` (see `src/main/resources/application.properties`).

## Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/ping` | Liveness endpoint (rate-limited) |
| GET | `/metrics` | Per-user request + rejection counters (not rate-limited) |
| GET | `/actuator/health` | Service health check |

## How it works

Each incoming request carries a `X-User-Id` header. Throttle checks 
whether that user has exceeded their configured request quota within 
the current sliding window. If yes, it returns HTTP 429. If no, it 
allows the request and records the timestamp atomically in Redis.

The sliding window algorithm prevents the boundary exploit present in 
fixed window implementations. The evict → count → add sequence runs 
as a single Lua script on Redis, making it safe under concurrent load 
and across multiple service instances.

## Running tests
```bash
./mvnw test
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

## License

No license file is currently included in this repository.
