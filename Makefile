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
	@echo "Building application..."
	./gradlew build

test:
	@echo "Running tests..."
	./gradlew test

clean:
	@echo "Cleaning build artifacts..."
	./gradlew clean

run:
	@echo "Running application locally..."
	./gradlew bootRun

# Code quality
ktlint:
	@echo "Running Kotlin linter..."
	./gradlew ktlintCheck

ktlint-fix:
	@echo "Fixing Kotlin lint issues..."
	./gradlew ktlintFormat

# Docker targets
docker-build:
	@echo "Building Docker image..."
	docker build -t fever-marketplace:latest .

docker-run: docker-build
	@echo "Running application in Docker..."
	docker run --rm -p 8080:8080 \
		-e DB_USER=fever \
		-e DB_PASSWORD=feverpass \
		-e REDIS_HOST=localhost \
		fever-marketplace:latest

# Docker Compose targets
docker-compose-up:
	@echo "Starting all services with docker-compose..."
	docker-compose up -d

docker-compose-down:
	@echo "Stopping all services..."
	docker-compose down

docker-logs:
	@echo "Showing docker-compose logs..."
	docker-compose logs -f

docker-ps:
	@echo "Showing running containers..."
	docker-compose ps

# Development workflow
dev-setup: clean build test ktlint
	@echo "Development setup complete!"

# CI/CD workflow
ci: clean build test ktlint docker-build
	@echo "CI pipeline complete!"

# Database operations
db-migrate:
	@echo "Running database migrations..."
	./gradlew flywayMigrate

db-reset:
	@echo "Resetting database..."
	docker-compose down
	docker volume rm $$(docker volume ls -q | grep postgres) || true
	docker-compose up -d postgres
	@echo "Waiting for database to be ready..."
	@sleep 10
	./gradlew flywayMigrate

# Container and data management
clean-containers:
	@echo "Stopping and removing all containers..."
	docker-compose down --remove-orphans

clean-volumes:
	@echo "Removing all volumes (This will DELETE all data!)..."
	docker-compose down -v

clean-images:
	@echo "Removing application images and build cache..."
	docker rmi fever-marketplace:latest 2>/dev/null || true
	docker builder prune -f
	docker image prune -f

clean-cache:
	@echo "Cleaning Docker build cache..."
	docker builder prune -a -f
	docker system prune -f

clean-all: clean-containers clean-volumes clean-images clean-cache
	@echo "Complete cleanup: containers, volumes, images, and cache removed"
	@echo "WARNING: All database data has been deleted!"

# Deep clean - removes everything including build cache
clean-deep:
	@echo "Deep cleaning: removing all containers, images, volumes, and build cache..."
	docker-compose down -v --remove-orphans
	docker rmi fever-marketplace:latest 2>/dev/null || true
	docker builder prune -a -f
	docker system prune -a -f --volumes
	@echo "Deep clean complete! All Docker resources cleaned."

# Nuclear option - clean everything Docker related
clean-nuclear:
	@echo "🔥 NUCLEAR CLEANUP - This will remove ALL Docker containers, images, volumes, and cache on your system!"
	@echo "Press Ctrl+C to cancel, or wait 10 seconds to proceed..."
	@sleep 10
	docker system prune -a --volumes -f
	docker builder prune -a -f
	@echo "Nuclear cleanup complete!"

# Rebuild from scratch (no cache)
rebuild:
	@echo "Rebuilding application from scratch (no cache)..."
	docker-compose down
	docker rmi fever-marketplace:latest 2>/dev/null || true
	docker-compose build --no-cache fever-marketplace
	@echo "Rebuild complete!"

# Fresh start with cache cleaning
fresh-start: clean-deep docker-compose-up
	@echo "Fresh start complete! All services rebuilt from scratch with clean cache."
	docker rmi fever-marketplace:latest 2>/dev/null || true
	docker image prune -f

clean-all: clean-containers clean-volumes clean-images
	@echo "Complete cleanup: containers, volumes, and images removed"
	@echo "WARNING: All database data has been deleted!"

reset-db: clean-volumes
	@echo "Resetting database (deleting all data)..."
	docker-compose up -d postgres
	@echo "Database reset complete. Run 'make db-migrate' to recreate tables."

reset-redis:
	@echo "Resetting Redis cache..."
	docker-compose restart redis
	@echo "Redis cache cleared"

# Quick cleanup (keeps data)
clean-soft:
	@echo "Soft cleanup: stopping containers but keeping data..."
	docker-compose down

# Nuclear option - clean everything Docker related
clean-nuclear:
	@echo "🔥 NUCLEAR CLEANUP - This will remove ALL Docker containers, images, and volumes on your system!"
	@echo "Press Ctrl+C to cancel, or wait 10 seconds to proceed..."
	@sleep 10
	docker system prune -a --volumes -f
	@echo "Nuclear cleanup complete!"

# Utility targets
logs:
	@echo "Showing application logs..."
	docker-compose logs -f fever-marketplace

health:
	@echo "Checking application health..."
	curl -f http://localhost:8080/actuator/health || echo "Application not healthy"

stop:
	@echo "Stopping all containers..."
	docker-compose down

restart: stop docker-compose-up
	@echo "Services restarted!"

# Fresh start - clean and rebuild everything
fresh-start: clean-all docker-compose-up
	@echo "Fresh start complete! All services rebuilt from scratch."

# Check if gradlew is executable
check-gradlew:
	@if [ ! -x "./gradlew" ]; then \
		echo "Making gradlew executable..."; \
		chmod +x ./gradlew; \
	fi

# Ensure gradlew is executable for all gradle commands
build test clean run ktlint ktlint-fix db-migrate: check-gradlew

# Kill any process running on port 8080 (local app)
kill-local:
	@echo "Killing any process running on port 8080..."
	@lsof -ti:8080 | xargs kill -9 2>/dev/null || echo "No process found on port 8080"

# Stop everything - both Docker and local processes
stop-all: stop kill-local
	@echo "Stopped all Docker containers and local processes"

# Check what's running on port 8080
check-port:
	@echo "Checking what's running on port 8080..."
	@lsof -i:8080 || echo "Nothing running on port 8080"

# Show running processes related to Java/Spring Boot
check-java:
	@echo "Java processes running:"
	@ps aux | grep java | grep -v grep || echo "No Java processes found"
