# Fever Marketplace

A high-performance event marketplace service built with **Spring Boot** and **Hexagonal Architecture**, featuring intelligent monthly bucket caching.

## 🏗️ Architecture

### Hexagonal Architecture (Ports & Adapters)

Clean separation of concerns with domain logic isolated from infrastructure:

- **Domain**: Event aggregate and business rules
- **Application**: Use cases (FindEvents, SyncEvents)
- **Infrastructure**: REST API, PostgreSQL, Redis cache, Retrofit HTTP client

### Monthly Bucket Cache Strategy

Intelligent caching that buckets events by month with excellent performance characteristics:

- **Smart Bucketing**: Events cached by month (e.g., `2024-12`, `2025-01`)
- **Partial Cache Hits**: Serves multi-month queries even with partial cache coverage
- **Tiered TTL**: Current month (2h), recent months (6h), old months (7 days)
- **Bounded Memory**: Memory usage scales with months, not events
- **Synchronize fresh data**: Every 30 seconds with external endpoint to populate database and cache

Example: Query spans Nov-Jan, only November cached → returns November from cache + Dec/Jan from database.

## 🔧 Technology Stack

- **Backend**: Spring Boot 3.2, Spring Data JDBC
- **Database**: PostgreSQL 15 with Flyway migrations
- **Cache**: Redis 7 with monthly bucket strategy
- **HTTP Client**: Retrofit 2.11 with XML support
- **Resilience**: Circuit breaker, retry, timeout patterns
- **Testing**: JUnit 5, Testcontainers, WireMock

## 🚀 Quick Start

### Prerequisites
- Java 21+
- Docker & Docker Compose

### Run with Docker
```bash
# Start all services
make docker-compose-up
# or: docker-compose up -d

# Health check
curl http://localhost:8080/actuator/health

# Search events
curl "http://localhost:8080/search?starts_at=2024-12-01T00:00:00&ends_at=2024-12-31T23:59:59"
```

### Local Development
```bash
# Start dependencies
docker-compose up -d postgres redis

# Run application
make run
# or: ./gradlew bootRun

# Run tests
make test
```

## 📋 Key Commands

```bash
# Development
make build          # Build application
make test           # Run tests
make run            # Run locally
make ktlint         # Code linting

# Docker
make docker-compose-up     # Start all services
make docker-compose-down   # Stop services
make logs                  # View logs

# Database
make db-migrate     # Run migrations
make db-reset       # Reset database (⚠️ deletes data)

# Cleanup
make clean-all      # Remove containers, volumes, images
make fresh-start    # Clean rebuild
```

## 🔧 Configuration

Key settings in `application.yml`:

```yaml
fever:
  cache:
    bucket:
      ttl-hours: 6                    # Standard TTL
      current-month-ttl-hours: 2      # Current month TTL
      max-buckets-per-query: 24       # Query span limit
      enable-tiered-ttl: true         # Smart TTL strategy
  sync:
    enabled: true
    interval: 30000                   # External sync interval (30s)
```

Environment variables:
```bash
DB_USER=fever
DB_PASSWORD=feverpass
REDIS_HOST=localhost
```

## 🔍 API

### Search Events
```bash
GET /search?starts_at=2024-12-01T00:00:00&ends_at=2024-12-31T23:59:59
```

Response:
```json
{
  "data": {
    "events": [
      {
        "id": "uuid",
        "title": "Concert in Madrid",
        "start_date": "2024-12-15",
        "start_time": "20:00:00",
        "end_date": "2024-12-15", 
        "end_time": "23:00:00",
        "min_price": 25.00,
        "max_price": 100.00
      }
    ]
  }
}
```

## 🏃‍♂️ Performance & Concurrency

### Database Concurrency
- **HikariCP connection pooling** for efficient connection management
- **Event hash-based deduplication** prevents conflicts
- **Append-only design** minimizes lock contention

### Cache Performance
- **Redis atomic operations** ensure thread safety
- **Async cache population** doesn't block requests
- **Partial cache hits** provide graceful degradation

## 🧪 Testing

```bash
# All tests
make test

# Specific categories
./gradlew test --tests "*UnitTest*"
./gradlew test --tests "*IntegrationTest*"
```

## 📊 Future Improvements

### High Priority
1. **Enhanced Monitoring**: Prometheus metrics, cache hit/miss ratios, database performance
2. **Cache Optimization**: Cache warming, compression, intelligent pre-loading

### Medium Priority
1. **API Enhancements**: Pagination, filtering, sorting
2. **Security**: Authentication, rate limiting, input validation
3. **Database**: Read replicas, partitioning for historical data

## 🚨 Known Issues

- `FeverMarketplaceFullIntegrationTest` has Spring context loading issues due to booting up for each different test class but test logic is valid, during build it might be flaky.
- **Workaround**: Use shared resources for containers with other integration tests

## 🛠️ Architecture Benefits

- **Maintainable**: Clear layer separation and dependency inversion
- **Testable**: Easy mocking via ports and adapters pattern
- **Scalable**: Monthly bucket cache strategy handles high loads efficiently
- **Resilient**: Circuit breakers and retries for external dependencies
- **Performant**: Intelligent caching reduces database load significantly

Built with Spring Boot + Hexagonal Architecture + Smart Caching
