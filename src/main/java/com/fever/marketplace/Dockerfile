# Multi-stage build for Spring Boot application
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy gradle files for dependency caching
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

# Download dependencies (cached layer)
RUN gradle dependencies --no-daemon

# Copy source code
COPY src ./src

# Build application
RUN gradle bootJar --no-daemon

# Runtime stage
FROM openjdk:21-jre-slim

WORKDIR /app

# Create non-root user for security
RUN addgroup --system --gid 1001 fever && \
    adduser --system --uid 1001 --gid 1001 fever

# Copy built jar from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Change ownership to fever user
RUN chown fever:fever app.jar

# Switch to non-root user
USER fever

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Expose port
EXPOSE 8080

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
