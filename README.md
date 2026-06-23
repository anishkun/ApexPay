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

* **Language:** Java 21
* **Framework:** Spring Boot 3.5.x (Web, Data JPA, AMQP, Validation)
* **Database:** PostgreSQL 16 (Running in Docker); H2 in-memory for tests
* **Message Broker:** RabbitMQ 3-Management (Running in Docker)
* **JSON Processing:** Jackson ObjectMapper
* **Testing:** JUnit 5, Spring Boot Test, Testcontainers (RabbitMQ)
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

## ✅ Build & Test

The test suite is self-contained — it needs **no external infrastructure**:

```sh
mvn test
```

* Application logic runs against an in-memory **H2** database, so Postgres is not required.
* The outbox relay logic is covered by a deterministic unit test with a mocked broker.
* A **Testcontainers** integration test (`OutboxRabbitIntegrationTest`) spins up a throwaway
  RabbitMQ broker in Docker and verifies the full outbox → exchange → queue delivery path.
  It **auto-skips** when no Docker daemon is available, so `mvn test` stays green offline.

Build a runnable jar with `mvn clean package` (jar lands in `target/`).

## 💻 Local Setup & Running

> Running the live application (unlike the tests) requires Postgres and RabbitMQ.

1. Start Infrastructure (Docker)

The quickest way is the bundled compose file:

```sh
docker compose up -d
```

This starts PostgreSQL (`ApexDB`, user `postgres` / `password`) and RabbitMQ with the
management UI at http://localhost:15672 (guest/guest). Equivalent manual commands:

```sh
docker run -d --name apexpay-postgres -e POSTGRES_DB=ApexDB -e POSTGRES_PASSWORD=password -p 5432:5432 postgres:16
docker run -d --name apexpay-rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3.13-management
```

2. Configure Database Triggers (optional, for tamper-proofing)
For a production-grade immutable audit trail, access the PostgreSQL instance and apply the
trigger below. The application also enforces immutability at the code tier (no setters), so
this step is optional for a local run:

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
3. Run the Application

```sh
mvn spring-boot:run
```

The app exposes endpoints on `localhost:8080`. The `OutboxRelayWorker` polls the outbox table
every 5 seconds and publishes pending events to RabbitMQ; `RabbitMQConsumer` logs anything that
lands on the AI queue.

## 📖 API Documentation

| Method | Path | Description |
| ------ | ---- | ----------- |
| `POST` | `/api/v1/accounts` | Open an account (also writes a `CREATED` audit entry) |
| `GET`  | `/api/v1/accounts/{id}` | Fetch an account / check its balance |
| `POST` | `/api/v1/transfers` | Execute an idempotent transfer (requires `Idempotency-Key` header) |
| `GET`  | `/api/v1/transfers/{id}` | Query a payment (transaction) by id |
| `GET`  | `/api/v1/audit-logs/{entityId}` | List the immutable audit entries for an account |

### 1. Create an Account → `201 Created`
```text
POST /api/v1/accounts
Content-Type: application/json

{
    "userId": "11111111-1111-1111-1111-111111111111",
    "initialBalance": 1000.00,
    "currency": "USD"
}
```

### 2. Execute an Idempotent Transfer → `200 OK`
Transfers funds safely between two accounts. If the `Idempotency-Key` has been seen before, the
existing transaction is returned without deducting funds twice.

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

### 3. Query a Payment → `200 OK`
```text
GET /api/v1/transfers/<TRANSACTION_UUID>
```

### 4. Check Account Balance → `200 OK`
```text
GET /api/v1/accounts/<ACCOUNT_UUID>
```

### 5. Read the Audit Trail → `200 OK`
```text
GET /api/v1/audit-logs/<ACCOUNT_UUID>
```

### Error Model
Errors return a consistent JSON body with the appropriate HTTP status:

| Status | When |
| ------ | ---- |
| `400 Bad Request` | Missing `Idempotency-Key` header or request-body validation failure |
| `404 Not Found` | Unknown account or transaction |
| `422 Unprocessable Entity` | Insufficient funds in the source account |

```json
{
  "timestamp": "2026-06-22T21:00:00",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Insufficient funds in the source account",
  "fieldErrors": null
}
```
