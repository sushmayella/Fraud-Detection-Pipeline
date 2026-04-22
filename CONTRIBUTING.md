# Contributing

Thanks for your interest in contributing. This document explains how to get set up, what's expected in a pull request, and how the repo is organized for contribution.

## Table of contents

- [Development setup](#development-setup)
- [Running the stack locally](#running-the-stack-locally)
- [Making changes](#making-changes)
- [Commit conventions](#commit-conventions)
- [Pull request expectations](#pull-request-expectations)
- [Testing](#testing)
- [Code style](#code-style)
- [Architecture Decision Records](#architecture-decision-records)

## Development setup

### Prerequisites

- **Docker** 24+ and Docker Compose v2
- **Java** 17 (Temurin distribution recommended) + **Maven** 3.9+
- **Python** 3.11+ (for the `ml-scorer` service)
- **Make** (optional, used for convenience targets)
- ~8 GB free RAM to run the full stack

### Clone and bootstrap

```bash
git clone https://github.com/<your-username>/fraud-detection-platform.git
cd fraud-detection-platform
make bootstrap        # installs git hooks, pulls base images, compiles shared events
```

If `make` isn't available, the bootstrap is equivalent to:

```bash
mvn -pl shared/events install -DskipTests
docker-compose pull
```

## Running the stack locally

```bash
make up               # docker-compose up -d
make seed             # populate demo data
make logs             # tail logs for all services
make down             # tear down
```

The first run pulls Kafka, Schema Registry, Postgres, Redis, Prometheus, and Grafana images — expect ~3 minutes. Subsequent starts take ~20 seconds.

See [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) for what each component does and how they're wired together.

## Making changes

1. **Fork** the repo and create a feature branch from `main`:
   ```bash
   git checkout -b feat/short-description
   ```
2. **Branch naming:** `feat/...`, `fix/...`, `docs/...`, `refactor/...`, `test/...`, `chore/...`.
3. **Write the change** along with the test. If you can't write a test for it, open an issue first so we can discuss the right seam.
4. **Run the full test suite** locally before pushing — CI will run it, but catching failures locally is faster.
5. **Open a pull request** against `main` with a clear description (see below).

## Commit conventions

We follow [Conventional Commits](https://www.conventionalcommits.org/). The first line is `<type>(<scope>): <summary>`.

**Types:** `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `perf`, `build`, `ci`.

**Scopes** map to the service or cross-cutting concern:
- `ingestion-api`, `feature-extractor`, `rule-engine`, `ml-scorer`, `decision-engine`, `query-api`
- `events` (shared Avro schemas), `infra`, `docs`, `ci`

**Examples:**

```
feat(ingestion-api): add idempotency key support for duplicate submissions
fix(feature-extractor): correct window boundary off-by-one in velocity calc
docs(adr): add ADR-0004 explaining Redis TTL strategy
refactor(decision-engine): extract score combiner to pure function
test(rule-engine): add integration test for composite rule evaluation
```

The body (optional, separated by a blank line) explains **why** — what problem the change solves, what tradeoffs it makes. Reviewers read commit messages; future you reads them during incidents.

## Pull request expectations

A good PR on this repo:

- **Title** follows the commit convention (`feat(scope): summary`).
- **Description** answers: *What does this change? Why? What did you consider and reject?*
- **Scope** is focused — one logical change per PR. If you find yourself writing "also…" in the description, split the PR.
- **Tests** accompany the change. Untested changes to the decision or rule engines will be asked back.
- **CI is green** before requesting review.
- **Docs updated** when behavior or interfaces change (README, ARCHITECTURE, service-specific READMEs, OpenAPI specs).
- **Screenshots or logs** attached for changes affecting dashboards, error messages, or API responses.

PRs are squash-merged. Your commit history inside the PR branch can be messy; the final squash message will be the PR title and description.

## Testing

### Unit tests

Fast, isolated, no external dependencies.

```bash
mvn test
```

### Integration tests

Spin up real dependencies (Kafka, Postgres, Redis) via Testcontainers. Slower but verify actual wire behavior.

```bash
mvn verify
```

### ML scorer tests

```bash
cd services/ml-scorer
pip install -e ".[dev]"
pytest
```

### Coverage

CI gates PRs on **80% line coverage** for new or modified code. Run locally:

```bash
mvn verify
open services/ingestion-api/target/site/jacoco/index.html
```

### Contract tests

If your change touches a Kafka topic schema or a REST API, run the contract tests:

```bash
./scripts/contract-test.sh
```

## Code style

- **Java:** we use [Spotless](https://github.com/diffplug/spotless) with the Google Java Format. Run `mvn spotless:apply` before committing. CI will fail on style violations.
- **Python:** Black + Ruff, both configured in `services/ml-scorer/pyproject.toml`. Run `make lint-py`.
- **Avro:** schema evolution must be backward-compatible. The Schema Registry enforces this on PR via a CI check.

## Architecture Decision Records

Significant design decisions are captured as ADRs in [`docs/adr/`](./docs/adr/). Open a new ADR when you're:

- Introducing a new service or major technology
- Changing a cross-cutting pattern (serialization, error handling, auth)
- Making a tradeoff whose reasoning needs to outlive the PR

Use the template at [`docs/adr/0000-template.md`](./docs/adr/0000-template.md). ADRs are immutable once merged — if an earlier decision is reversed, write a new ADR that supersedes it.

## Getting help

- Open a [GitHub Discussion](../../discussions) for design questions.
- Open an [Issue](../../issues) for bugs or concrete feature requests.
- Check the [ADR index](./docs/adr/README.md) before proposing structural changes — the answer may already be there.
