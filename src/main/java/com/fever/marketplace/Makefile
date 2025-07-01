# Fever Marketplace Makefile
.PHONY: help build test clean run docker-build docker-run docker-compose-up docker-compose-down ktlint ktlint-fix

# Default target
.DEFAULT_GOAL := help

help:
	@echo "Available targets:"
	@echo "  build           - Build the application"
	@echo "  test            - Run tests"
	@echo "  clean           - Clean build artifacts"
	@echo "  run             - Run the application locally"
	@echo "  ktlint          - Run Kotlin linter"
	@echo "  ktlint-fix      - Fix Kotlin lint issues"
	@echo "  docker-build    - Build Docker image"
	@echo "  docker-run      - Run application in Docker"
	@echo "  docker-compose-up   - Start all services with docker-compose"
	@echo "  docker-compose-down - Stop all services"

# Application build targets
build:
	./gradlew build

test:
	./gradlew test

clean:
	./gradlew clean

run:
	./gradlew bootRun

# Code quality
ktlint:
	./gradlew ktlintCheck

ktlint-fix:
	./gradlew ktlintFormat

# Docker targets
docker-build:
	docker build -t fever-marketplace:latest .

docker-run: docker-build
	docker run --rm -p 8080:8080 \
		-e DB_USER=fever \
		-e DB_PASSWORD=feverpass \
		-e REDIS_HOST=localhost \
		fever-marketplace:latest

# Docker Compose targets
docker-compose-up:
	docker-compose up -d

docker-compose-down:
	docker-compose down

docker-logs:
	docker-compose logs -f

# Development workflow
dev-setup: clean build test ktlint

# CI/CD workflow
ci: clean build test ktlint docker-build
