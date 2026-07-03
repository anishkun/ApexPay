-- V3: end-to-end correlation ID on the outbox row.
--
-- The relay publishes on a scheduler thread that has no HTTP request context, so
-- the correlation ID captured at request time must be persisted on the outbox row
-- to survive the async hop. Nullable: pre-existing rows and non-HTTP writers have
-- none. The column name/type mirrors what Hibernate maps for OutboxEvent
-- (snake_case, String -> VARCHAR), so ddl-auto=validate still passes.

ALTER TABLE outbox_events
    ADD COLUMN correlation_id VARCHAR(255);
