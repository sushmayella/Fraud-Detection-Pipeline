#!/usr/bin/env bash
# Initializes the repo with a clean, chunked commit history.
# Run ONCE after extracting the scaffold into a fresh directory.
#
# Usage:
#   cd /path/to/extracted/fraud-detection-platform
#   ./scripts/init-repo.sh
#
# After this runs, you can push to an EMPTY GitHub repo.
# If pushing to an existing repo with content, you'll need to force-push
# (acceptable while the repo is effectively empty).

set -euo pipefail

if [[ -d .git ]]; then
  echo "This directory already has a .git folder."
  echo "Delete it first (rm -rf .git) if you want to start fresh."
  exit 1
fi

# Use a stable author identity — edit these to match yours before committing
AUTHOR_NAME="${GIT_AUTHOR_NAME:-Sushma Yella}"
AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-spoorthysushma@gmail.com}"

git init -b main
git config user.name "$AUTHOR_NAME"
git config user.email "$AUTHOR_EMAIL"

echo ""
echo "Building up commit history in logical chunks..."
echo ""

# 1. Repo hygiene — baseline everything someone needs to understand the project
git add .gitignore LICENSE README.md
git commit -m "chore: initial repo hygiene (gitignore, license, readme)"

# 2. Docs — the "thinking behind the code"
git add CONTRIBUTING.md docs/
git commit -m "docs: add architecture, contributing guide, and initial ADRs

- Full ARCHITECTURE.md covering components, invariants, failure modes, scaling
- 3 ADRs: Kafka vs RabbitMQ, Kafka Streams vs Flink, in-house rule DSL
- Sequence diagrams for happy path, ML timeout, DLQ retry, idempotent replay
- Contributor guide with commit conventions and PR expectations"

# 3. Build infrastructure
git add pom.xml Makefile
git commit -m "build: add parent Maven POM with Java 17, Spring Boot 3.2, JaCoCo 80% gate"

# 4. Shared Avro event schemas — the contract between services
git add shared/
git commit -m "feat(events): add Avro schemas for Transaction, EnrichedTransaction, Decision"

# 5. Ingestion API skeleton
git add services/ingestion-api/pom.xml \
        services/ingestion-api/src/main/java/com/fraudplatform/ingestion/IngestionApiApplication.java \
        services/ingestion-api/src/main/resources/application.yml
git commit -m "feat(ingestion-api): initial Spring Boot skeleton with actuator + prometheus"

# 6. Ingestion API — DTOs and controller
git add services/ingestion-api/src/main/java/com/fraudplatform/ingestion/api/
git commit -m "feat(ingestion-api): add REST endpoint with validation + OpenAPI docs

POST /api/v1/transactions accepts a transaction for fraud evaluation.
Returns 202 with an acknowledgement containing the transaction id
and the eventual decision URL. Input validated via Jakarta Validation."

# 7. Ingestion API — Kafka integration
git add services/ingestion-api/src/main/java/com/fraudplatform/ingestion/config/KafkaConfig.java \
        services/ingestion-api/src/main/java/com/fraudplatform/ingestion/service/TransactionService.java
git commit -m "feat(ingestion-api): publish transactions to raw-transactions topic

- Idempotent producer config (acks=all, enable.idempotence=true)
- Avro serialization via Confluent Schema Registry
- Keyed by transactionId for ordering guarantees across retries
- Micrometer counter for accepted transactions"

# 8. Ingestion API — tests
git add services/ingestion-api/src/test/
git commit -m "test(ingestion-api): add unit + Testcontainers integration tests

- Unit: TransactionService verifies id generation, key-by-txn-id, and event fields
- Integration: real Kafka via Testcontainers, verifies end-to-end POST → topic"

# 9. Dockerfile
git add services/ingestion-api/Dockerfile
git commit -m "build(ingestion-api): multi-stage Dockerfile with JRE-alpine runtime"

# 10. Docker compose + infra
git add docker-compose.yml infra/
git commit -m "infra: docker-compose stack with Kafka, Schema Registry, Postgres, Redis, Prometheus, Grafana"

# 11. Scripts
git add scripts/
git commit -m "chore(scripts): add seed-transactions helper and repo-init script"

# 12. CI
git add .github/
git commit -m "ci: add GitHub Actions workflow

- Runs Spotless format check, unit tests, Testcontainers integration tests
- Uploads JaCoCo coverage and test reports as artifacts
- Builds Docker image on green main"

echo ""
echo "✓ Done. Commit history:"
git log --oneline
echo ""
echo "Next steps:"
echo "  1. Verify git log looks clean:  git log --stat"
echo "  2. Add the GitHub remote:       git remote add origin git@github.com:sushmayella/Fraud-Detection-Pipeline.git"
echo "  3. Force-push (if repo has old content):  git push -u origin main --force"
echo "     OR push normally (if repo is empty):   git push -u origin main"
