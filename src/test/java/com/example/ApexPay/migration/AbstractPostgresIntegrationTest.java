package com.example.ApexPay.migration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Reusable base for integration tests that need to run against a REAL Postgres
 * instead of the fast in-memory H2 used by the rest of the suite.
 *
 * <p>It boots the full Spring context with the {@code pg} profile (see
 * {@code src/test/resources/application-pg.properties}), which flips the test
 * defaults to the production-like pipeline: <b>Flyway enabled</b>,
 * <b>ddl-auto=validate</b>, and the <b>Postgres dialect</b>. The datasource is
 * pointed at a throwaway {@link PostgreSQLContainer} via
 * {@link DynamicPropertySource}.
 *
 * <p>The container image is pinned to {@code postgres:16-alpine} to match the
 * project's docker-compose. {@code @Testcontainers(disabledWithoutDocker = true)}
 * makes every subclass self-skip when no Docker daemon is reachable, so
 * {@code mvn test} stays green on machines without Docker.
 */
@SpringBootTest
@ActiveProfiles("pg")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgresIntegrationTest {

    /**
     * <b>Singleton container</b> (manually started, deliberately NOT managed by the
     * {@code @Container} JUnit lifecycle). When more than one test class extends this
     * base, the per-class {@code @Container} lifecycle would stop the container after
     * the first class finished, while Spring's cached application context (with its
     * Hikari pool still pointed at that now-dead container) gets reused by the next
     * class — yielding "Connection refused". Starting it once in a static initializer
     * keeps a single Postgres up for the whole JVM, matching the single cached context
     * the shared {@link DynamicPropertySource} values resolve to. The container is
     * reaped by Testcontainers' Ryuk at JVM exit.
     */
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        // Only start when Docker is reachable. On a Docker-less machine the
        // @Testcontainers(disabledWithoutDocker = true) extension self-skips every
        // subclass, but this static initializer runs at class-load time (before the
        // skip), so we must guard it to avoid throwing there.
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
