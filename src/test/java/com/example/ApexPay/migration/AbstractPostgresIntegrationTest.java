package com.example.ApexPay.migration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
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

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
