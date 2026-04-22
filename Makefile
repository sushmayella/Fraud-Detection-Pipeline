.PHONY: help build up down logs seed test coverage clean format

help:
	@echo "Targets:"
	@echo "  build       Build the ingestion-api + shared events"
	@echo "  up          Start the full stack via docker-compose"
	@echo "  down        Stop the stack and remove volumes"
	@echo "  logs        Tail logs from all running services"
	@echo "  seed        Seed demo transactions"
	@echo "  test        Run unit tests"
	@echo "  coverage    Run tests + generate JaCoCo coverage report"
	@echo "  format      Auto-format Java with Spotless"
	@echo "  clean       Clean Maven + Docker state"

build:
	mvn clean install -DskipTests
	docker-compose build

up:
	docker-compose up -d
	@echo ""
	@echo "Services starting. Once healthy:"
	@echo "  Ingestion API: http://localhost:8080/swagger-ui.html"
	@echo "  Kafka UI:      http://localhost:8090"
	@echo "  Grafana:       http://localhost:3000 (admin/admin)"
	@echo "  Prometheus:    http://localhost:9090"

down:
	docker-compose down -v

logs:
	docker-compose logs -f --tail=100

seed:
	./scripts/seed-transactions.sh

test:
	mvn test

coverage:
	mvn verify
	@echo "Coverage: services/ingestion-api/target/site/jacoco/index.html"

format:
	mvn spotless:apply

clean:
	mvn clean
	docker-compose down -v --remove-orphans
