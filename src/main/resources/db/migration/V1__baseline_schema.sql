-- V1 baseline schema for ApexPay.
--
-- This migration is the source of truth for the production Postgres schema.
-- It is authored to match exactly what the JPA entities map to, so that
-- Hibernate runs with spring.jpa.hibernate.ddl-auto=validate in prod.
--
-- Column names follow Hibernate's default snake_case physical naming
-- strategy (e.g. userId -> user_id). Types mirror the Postgres dialect DDL
-- Hibernate generates for these entities.

-- accounts: Account entity (UUID PK, optimistic-lock version column).
CREATE TABLE accounts (
    id        UUID           NOT NULL,
    user_id   UUID           NOT NULL,
    balance   NUMERIC(19, 4) NOT NULL,
    currency  VARCHAR(3)     NOT NULL,
    version   BIGINT,
    PRIMARY KEY (id)
);

-- transactions: Transaction entity (status persisted as STRING enum).
CREATE TABLE transactions (
    id                     UUID           NOT NULL,
    source_account_id      UUID           NOT NULL,
    destination_account_id UUID           NOT NULL,
    amount                 NUMERIC(19, 4) NOT NULL,
    status                 VARCHAR(255)   NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    timestamp              TIMESTAMP(6)   NOT NULL,
    PRIMARY KEY (id)
);

-- outbox_events: OutboxEvent entity (transactional outbox pattern).
CREATE TABLE outbox_events (
    id             UUID          NOT NULL,
    aggregate_type VARCHAR(255)  NOT NULL,
    aggregate_id   UUID          NOT NULL,
    event_type     VARCHAR(255)  NOT NULL,
    payload        TEXT          NOT NULL,
    processed      BOOLEAN       NOT NULL,
    created_at     TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id)
);

-- idempotency_records: IdempotencyRecord entity (idempotency key is the PK).
CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(100) NOT NULL,
    transaction_id  UUID         NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (idempotency_key)
);

-- audit_logs: AuditLog entity (action persisted as STRING enum).
CREATE TABLE audit_logs (
    id             UUID         NOT NULL,
    entity_id      UUID         NOT NULL,
    action         VARCHAR(255) NOT NULL CHECK (action IN ('CREDIT', 'DEBIT', 'CREATED')),
    previous_state TEXT,
    new_state      TEXT,
    timestamp      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);
