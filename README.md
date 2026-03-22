# Throttle

A production-grade API rate limiting service built in Java 17 and Spring Boot.
Throttle enforces per-user request quotas using a sliding window algorithm,
with Redis as a distributed backing store and a real-time metrics endpoint.

## Tech Stack

Java 17 · Spring Boot 3.2 · Redis · Docker · JUnit 5 · Maven

## How it works

Each incoming request carries a user ID header. Throttle checks whether that
user has exceeded their configured request quota within the current time window.
If yes, it returns HTTP 429 Too Many Requests. If no, it allows the request
through and records the timestamp.

The sliding window algorithm prevents the boundary exploit present in fixed
window implementations — quotas are always calculated relative to the current
moment, not a fixed reset time.

## Running locally

**Prerequisites:** Java 17, Docker Desktop
```bash
# Start Redis
docker compose up redis

# Run the app
./mvnw spring-boot:run
```

The API is available at `http://localhost:8080`.
Send requests with an `X-User-Id` header to test rate limiting.

## Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/ping` | Test endpoint — returns 200 or 429 |
| GET | `/metrics` | Per-user request and rejection stats |
| GET | `/actuator/health` | Service health check |

## Running tests
```bash
./mvnw test
```

## Project structure
```
src/main/java/com/sanikasurose/throttle/
├── core/        # Rate limiting logic — no Spring dependencies
├── metrics/     # Per-user stats tracking
└── web/         # Spring filter and REST controllers
```

## Design decisions

- **Sliding window** over fixed window — prevents burst exploitation at window boundaries
- **Per-record locking** over global lock — users never block each other
- **Clock interface** injected for deterministic, sleep-free unit tests
- **Redis backing store** enables horizontal scaling across multiple instances