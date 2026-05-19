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

2. Configure Database Triggers
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
3. Run the Application
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