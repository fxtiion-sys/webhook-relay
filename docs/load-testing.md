# Load Testing and Performance Benchmarks

## 1. Overview

Load testing was conducted using k6 to evaluate event ingestion performance, asynchronous delivery reliability, and horizontal worker scalability.

### Objectives

- Measure event ingestion API latency under sustained load.
- Verify delivery completeness and success rates.
- Identify asynchronous delivery processing bottlenecks.
- Evaluate the impact of horizontal worker scaling.
- Validate delivery metrics against PostgreSQL records.

## 2. Test Environment

- Java 21 and Spring Boot
- PostgreSQL 17 running in Docker
- k6 v2.4.0
- Spring Boot Actuator and Micrometer Prometheus metrics
- Local development environment
- Shared PostgreSQL delivery queue
- Local mock webhook receiver
- Worker polling interval: 500 ms

All tests used an isolated event type and unique idempotency keys.

## 3. Running the Load Tests

Start the required Docker services:

```bash
docker compose up -d
```

Start the first application instance:

```bash
set -a
source .env
set +a

APP_PORT=8080 APP_WORKER_ID=worker-1 \
  ./gradlew bootRun --args='--spring.profiles.active=local'
```

### Smoke Test

```bash
k6 run -e RATE=2 -e DURATION=5s scripts/load-test.js
```

### Baseline Test

```bash
k6 run -e RATE=5 -e DURATION=30s scripts/load-test.js
```

### Single-worker Load Test

```bash
k6 run -e RATE=20 -e DURATION=60s scripts/load-test.js
```

### Multi-worker Load Test

Start a second application instance in a separate terminal:

```bash
set -a
source .env
set +a

APP_PORT=8081 APP_WORKER_ID=worker-2 \
  ./gradlew bootRun --args='--spring.profiles.active=local'
```

Verify both instances are healthy, then repeat the same workload:

```bash
k6 run -e RATE=20 -e DURATION=60s scripts/load-test.js
```

Event ingestion requests continue to target port 8080. Both workers consume deliveries from the same PostgreSQL database.

## 4. Performance Thresholds

The k6 script defines the following acceptance criteria:

| Metric | Threshold |
|---|---|
| Event ingestion p95 latency | Below 500 ms |
| HTTP failure rate | Below 1% |
| Response checks | 100% passed |
| Dropped iterations | 0 |

All thresholds passed during the baseline, single-worker, and multi-worker load tests.

The smoke test API summary was not retained.

## 5. Benchmark Results

### Event Ingestion Performance

| Metric | Smoke | Baseline | Single-worker | Multi-worker |
|---|---:|---:|---:|---:|
| Workers | 1 | 1 | 1 | 2 |
| Target events/sec | 2 | 5 | 20 | 20 |
| Duration | 5s | 30s | 60s | 60s |
| Published events | 11 | 151 | 1,201 | 1,201 |
| API average (ms) | N/A | 18.17 | 13.03 | 11.83 |
| API p95 (ms) | N/A | 25.74 | 17.46 | 17.39 |
| HTTP failure rate | N/A | 0% | 0% | 0% |
| Dropped iterations | N/A | 0 | 0 | 0 |

### End-to-End Delivery Performance

| Metric | Smoke | Baseline | Single-worker | Multi-worker |
|---|---:|---:|---:|---:|
| Successful deliveries | 11 | 151 | 1,201 | 1,201 |
| Success rate | 100% | 100% | 100% | 100% |
| Average latency (ms) | 409.43 | 321.57 | 8,555.02 | 321.41 |
| p50 latency (ms) | 406.33 | 327.19 | 8,663.93 | 330.71 |
| p95 latency (ms) | 625.91 | 548.64 | 16,045.88 | 531.41 |
| Maximum latency (ms) | 656.15 | 580.05 | 16,969.42 | 601.57 |
| Retries | 0 | 0 | 0 | 0 |
| Dead deliveries | 0 | 0 | 0 | 0 |
| Final pending deliveries | 0 | 0 | 0 | 0 |

Across all four tests, 2,564 events were successfully delivered without recorded retries or dead deliveries.

## 6. Bottleneck Analysis

At 20 events per second, the single-worker configuration maintained an event ingestion p95 latency of 17.46 ms. However, end-to-end delivery p95 latency increased to approximately 16 seconds.

This indicated that asynchronous delivery processing, rather than event ingestion, was the limiting stage under the tested workload.

The delivery worker performs several operations:

1. Poll and claim eligible deliveries.
2. Execute outbound HTTP requests.
3. Persist delivery outcomes.
4. Continue processing subsequent deliveries.

When incoming work exceeds processing capacity, deliveries accumulate in the queue and experience increased waiting time.

The precise contribution of polling, database operations, and sequential processing was not independently profiled.

## 7. Horizontal Worker Scaling

A second application instance was introduced without modifying the Java implementation.

Both workers consumed deliveries from the shared PostgreSQL queue using the existing `FOR UPDATE SKIP LOCKED` claim mechanism.

### Worker Distribution

| Worker | Successful deliveries | Share |
|---|---:|---:|
| worker-1 | 623 | 51.87% |
| worker-2 | 578 | 48.13% |
| Total | 1,201 | 100% |

### Performance Improvement

| Metric | Single-worker | Multi-worker | Reduction |
|---|---:|---:|---:|
| Average E2E latency | 8,555.02 ms | 321.41 ms | 96.24% |
| p50 E2E latency | 8,663.93 ms | 330.71 ms | 96.18% |
| p95 E2E latency | 16,