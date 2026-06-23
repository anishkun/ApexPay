-- V2: outbox relay resilience.
--
-- Adds the columns the resilient relay needs to do bounded retry with
-- exponential backoff, optimistic-locking against concurrent relays, and a
-- publish-failure dead-letter path. All columns are nullable or carry a
-- DEFAULT so the migration applies cleanly to existing outbox_events rows.
--
-- Column names/types mirror what Hibernate maps for the updated OutboxEvent
-- entity (snake_case physical naming, Postgres dialect), so ddl-auto=validate
-- still passes against the migrated schema.

ALTER TABLE outbox_events
    -- Relay lifecycle status (OutboxStatus enum persisted as STRING).
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    -- Number of publish attempts so far.
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    -- When the event becomes eligible to be relayed again (null = immediately).
    ADD COLUMN next_attempt_at TIMESTAMP(6),
    -- Last publish error for ops visibility on FAILED/DEAD rows.
    ADD COLUMN last_error TEXT,
    -- When the broker confirmed the publish (status -> PUBLISHED).
    ADD COLUMN published_at TIMESTAMP(6),
    -- Optimistic-lock guard so two racing relays can't double-publish a row.
    ADD COLUMN version BIGINT;

-- Backfill: any pre-existing already-relayed rows (processed = true) are
-- PUBLISHED; the DEFAULT already left unprocessed rows as PENDING.
UPDATE outbox_events SET status = 'PUBLISHED' WHERE processed = true;

-- Relay polls eligible rows oldest-first filtered by status + next_attempt_at.
CREATE INDEX idx_outbox_events_relay
    ON outbox_events (status, next_attempt_at, created_at);
