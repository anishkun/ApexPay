package com.example.ApexPay.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the Flyway baseline migration applies cleanly to a real Postgres AND
 * that Hibernate {@code ddl-auto=validate} passes against the migrated schema.
 *
 * <p>The strongest assertion here is implicit: if the Spring context fails to
 * load, either a migration failed or Hibernate's schema validation rejected the
 * V1 schema as not matching the JPA entities. So a successfully started context
 * is itself the core proof. The explicit assertions below pin that down further.
 */
class FlywayMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DataSource dataSource;

    /**
     * The context loaded, which means: (a) every Flyway migration applied, and
     * (b) Hibernate validate accepted V1 as matching the entities.
     */
    @Test
    void contextLoadsWithFlywayAndValidate() {
        assertNotNull(applicationContext, "Spring context should be up");
        // Flyway is wired as a bean only when migrations ran.
        assertTrue(applicationContext.containsBean("flyway"),
                "Flyway bean should exist (migrations enabled in the pg profile)");
    }

    @Test
    void allExpectedTablesExist() throws Exception {
        Set<String> expected = Set.of(
                "accounts", "transactions", "outbox_events",
                "idempotency_records", "audit_logs");

        Set<String> found = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT table_name FROM information_schema.tables " +
                             "WHERE table_schema = 'public'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getString("table_name").toLowerCase());
                }
            }
        }

        for (String table : expected) {
            assertTrue(found.contains(table),
                    () -> "expected migrated table '" + table + "' to exist; found: " + found);
        }
    }

    @Test
    void flywayHistoryShowsV1AppliedSuccessfully() throws Exception {
        boolean v1Applied = false;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT version, success FROM flyway_schema_history " +
                             "WHERE version = '1'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "flyway_schema_history should contain a V1 row");
                assertTrue(rs.getBoolean("success"),
                        "V1 migration should be recorded as success=true");
                v1Applied = true;
            }
        }
        assertTrue(v1Applied);
    }

    /**
     * Re-running Flyway against the already-migrated database must be a no-op:
     * no pending migrations, every applied migration in a success state. This is
     * the idempotency/repeatability check.
     */
    @Test
    void schemaIsUpToDateAndMigrationsAreIdempotent() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .load();

        MigrationInfo[] applied = flyway.info().applied();
        assertTrue(applied.length >= 1, "at least the V1 migration should be applied");
        for (MigrationInfo info : applied) {
            assertTrue(info.getState().isApplied(),
                    () -> "migration " + info.getVersion() + " should be in an applied state");
            assertNotEquals("FAILED", info.getState().name(),
                    () -> "migration " + info.getVersion() + " must not be in a FAILED state");
        }

        // Nothing should be pending against the migrated schema.
        MigrationInfo[] pending = flyway.info().pending();
        assertEquals(0, pending.length, "no migrations should be pending against a migrated schema");

        // A second migrate() is a no-op (returns 0 newly-applied migrations).
        int newlyApplied = flyway.migrate().migrationsExecuted;
        assertEquals(0, newlyApplied,
                "re-running migrate() against an up-to-date schema should apply nothing");

        // Sanity: only ranged usage so static analyzers don't flag the var.
        List<MigrationInfo> all = List.of(flyway.info().all());
        assertFalse(all.isEmpty(), "Flyway should report at least one migration");
    }
}
