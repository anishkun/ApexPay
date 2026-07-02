# 🏦 ApexPay: Distributed Event-Driven Financial Ledger

ApexPay is an enterprise-grade backend engine designed to process financial transactions with absolute data integrity. Built with Spring Boot, PostgreSQL, and RabbitMQ, it solves complex distributed system challenges including deadlocks, race conditions, double-spending, and the dual-write problem.

> **Note for Reviewers:** A distributed, event-driven financial ledger built with Spring Boot, PostgreSQL, and RabbitMQ, demonstrating Transactional Outbox, Idempotency, and Pessimistic Locking.

## 🚀 Key Architectural Features

* **Deadlock-Resistant Engine:** Implements lexicographical lock ordering (sorting UUIDs) to mathematically prevent database deadlocks during highly concurrent bi-directional transfers.
* **Double-Spend Prevention:** Utilizes JPA Pessimistic Write Locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) to serialize access to account rows, preventing race conditions.
* **Idempotency Facade:** Safely handles network glitches and client retries using an `Idempotency-Key` header. Duplicate requests return cached receipts without re-triggering the ledger.
* **Immutable Audit Trail:** Secures transaction logs at both the application tier (Lombok `@Builder` / no setters) and the database tier (PostgreSQL Triggers blocking `UPDATE` and `DELETE` commands).
* **Transactional Outbox Pattern:** Solves the dual-write problem by saving RabbitMQ events to an internal `outbox_events` table within the same ACID transaction boundary as the financial transfer.
* **Event-Driven Decoupling:** Employs a background relay worker (`@Scheduled`) to reliably publish JSON-serialized success events to RabbitMQ (`TopicExchange`), allowing decoupled microservices (e.g., AI Fraud Detection, Notifications) to consume data without impacting API latency.

## 🛠️ Tech Stack

* **Language:** Java 
* **Framework:** Spring Boot 3.x (Web, Data JPA, AMQP, Validation)
* **Database:** PostgreSQL 15+ (Running in Docker)
* **Message Broker:** RabbitMQ 3-Management (Running in Docker)
* **JSON Processing:** Jackson ObjectMapper
* **Tooling:** Lombok, Maven

## 📂 Project Structure

```text
src/main/java/com/example/ApexPay
 ├── controller/       # REST APIs (AccountController, TransferController)
 ├── service/          # Core Business Logic & Idempotency Facade
 ├── entity/           # JPA Domain Models (Account, Transaction, AuditLog, Outbox)
 ├── repository/       # Spring Data JPA Interfaces
 ├── config/           # Infrastructure Setup (RabbitMQ Topology)
 ├── event/            # Internal DTOs for Event Publishing
 └── worker/           # Background Cron Jobs (OutboxRelayWorker)
```

## 💻 Local Setup & Running
1. Start Infrastructure (Docker)
Ensure Docker is running, then spin up PostgreSQL and RabbitMQ:

```text
# Start PostgreSQL
docker run -d --name my-postgres -e POSTGRES_PASSWORD=password -p 5432:5432 postgres

# Start RabbitMQ with Management UI
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```
(RabbitMQ Dashboard available at http://localhost:15672 | guest/guest)

2. Configure Environment Variables
All connection settings and credentials are read from environment variables (see `.env.example` for the full list). For local dev the defaults match the Docker setup above — you only need to set the database password:

```text
DB_PASSWORD=password
```
No plaintext production secrets are committed; production deployments must override `DB_*` and `RABBITMQ_*` via the environment.

3. Configure Database Triggers
Before running the application, access the PostgreSQL instance and apply the immutability trigger for the audit logs:

```text
CREATE OR REPLACE FUNCTION prevent_audit_log_modification() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Modifying or deleting records in the audit_logs table is strictly prohibited.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER enforce_append_only_audit_logs
BEFORE UPDATE OR DELETE ON audit_logs
FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();
```
4. Run the Application
Start the Spring Boot server using Maven or your IDE. The app will expose endpoints on localhost:8080.
## 📖 API Documentation
1. Create an Account
```text
POST /api/v1/accounts
Content-Type: application/json

{
    "userId": "11111111-1111-1111-1111-111111111111",
    "initialBalance": 1000.00,
    "currency": "USD"
}
```
2. Execute an Idempotent Transfer
Transfers funds safely between two accounts. If the Idempotency-Key has been seen before, it returns the existing transaction without deducting funds twice.

```text
POST /api/v1/transfers
Content-Type: application/json
Idempotency-Key: req-unique-id-123

{
    "sourceAccountId": "<SOURCE_UUID>",
    "destinationAccountId": "<DESTINATION_UUID>",
    "amount": 100.00
}
```
3. Check Account Balance
```text
GET /api/v1/accounts/<ACCOUNT_UUID>
```

## 🔒 Production Hardening / Operations

This section covers the operational surface added during production hardening. All of it is broker-free testable (`.\mvnw.cmd test` runs 25 tests against H2 with a mocked broker — no live Postgres/RabbitMQ required).

### Required environment variables
All connection settings, credentials, and toggles are read from the environment. See [`.env.example`](.env.example) for the authoritative, commented list. No production secrets are committed; `.env` is gitignored. Copy it and fill in real values:

```text
cp .env.example .env   # then edit .env
```

Groups: `DB_*` (PostgreSQL), `RABBITMQ_*` (broker), `API_*`/`ACTUATOR_*` (Basic-auth credentials), `LOG_FORMAT`, `TRACE_SAMPLING`/`TRACING_EXPORT_ENABLED`/`OTLP_ENDPOINT` (tracing), `DDL_AUTO`/`SHOW_SQL` (JPA). Defaults are local/dev-only and must be overridden in production.

### Security model (HTTP Basic)
The payment API and sensitive actuator endpoints are protected by Spring Security HTTP Basic (stateless: CSRF disabled, no HTTP session). Two in-memory users are seeded from environment variables and BCrypt-encoded at startup — no password hashes are committed and passwords are never logged.

| User | Env vars | Roles | Can access |
|------|----------|-------|-----------|
| API caller | `API_USER` / `API_PASSWORD` | `ROLE_USER` | `/api/v1/**` |
| Ops caller | `ACTUATOR_USER` / `ACTUATOR_PASSWORD` | `ROLE_ACTUATOR` + `ROLE_USER` | `/actuator/prometheus`, `/actuator/metrics/**`, plus everything the API caller can reach |

**CHANGE IN PRODUCTION:** the `changeit` defaults exist only for local/dev/test. Real deployments MUST override all four credential env vars.

### Actuator endpoints
Only `health,info,prometheus,metrics` are exposed. Access split:

| Endpoint | Auth | Notes |
|----------|------|-------|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Public | Kubernetes/orchestrator probes. Readiness = `readinessState` + `db` only (broker outages are tolerated by the outbox pattern). Health details shown only `when-authorized`. |
| `/actuator/info` | Public | |
| `/actuator/prometheus`, `/actuator/metrics/**` | `ROLE_ACTUATOR` | Ops-only. |

### Metrics / Prometheus scraping
Micrometer exports metrics in Prometheus format at `GET /actuator/prometheus`. Configure your Prometheus job to scrape it with the ops credentials, e.g.:

```yaml
scrape_configs:
  - job_name: apexpay
    metrics_path: /actuator/prometheus
    basic_auth:
      username: ops        # ACTUATOR_USER
      password: <secret>   # ACTUATOR_PASSWORD
    static_configs:
      - targets: ['apexpay:8080']
```
Custom metrics cover the outbox relay worker and the RabbitMQ consumer alongside the JVM/HTTP defaults.

### Correlation IDs & distributed tracing
* **Correlation IDs:** every HTTP request gets a correlation ID (taken from the inbound `X-Correlation-Id` header, or generated if absent) that is echoed back on the response `X-Correlation-Id` header, placed in the logging MDC (`correlationId`), and propagated across the broker hop via the `x-correlation-id` AMQP header — so a single business flow is traceable across API, outbox relay, and consumer.
* **Structured logs:** `LOG_FORMAT=ecs` (default) emits ECS JSON with the correlation ID and traceId/spanId on every line; set `LOG_FORMAT=` (empty) for plain text in local dev.
* **Tracing:** Micrometer Tracing + the OpenTelemetry bridge populate traceId/spanId automatically. Span export is OFF by default (`TRACING_EXPORT_ENABLED=false`) — traces stay in-process but still appear in logs. To ship spans to a collector, set `TRACING_EXPORT_ENABLED=true` and point `OTLP_ENDPOINT` at your OTLP HTTP traces endpoint (e.g. `http://collector:4318/v1/traces`). Sampling is controlled by `TRACE_SAMPLING` (0.0–1.0).

### Dependency / vulnerability hygiene
All dependency versions are managed by the Spring Boot 3.5.13 parent BOM (no hardcoded versions, snapshots, or RCs in `pom.xml`). Test-only dependencies (`h2`, `spring-security-test`, `spring-rabbit-test`) are scoped `test` and do not ship in the runtime jar. To run an on-demand vulnerability scan (needs network access to the NVD, intentionally not wired into the build):

```text
mvn org.owasp:dependency-check-maven:check
```
