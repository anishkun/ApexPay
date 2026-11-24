-- V4: make audit_logs append-only at the database tier.
--
-- The README used to list this trigger as an optional manual step, so an existing
-- database may already have it: replace/drop first so the migration applies either way.

CREATE OR REPLACE FUNCTION prevent_audit_log_modification() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Modifying or deleting records in the audit_logs table is strictly prohibited.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS enforce_append_only_audit_logs ON audit_logs;

CREATE TRIGGER enforce_append_only_audit_logs
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();
