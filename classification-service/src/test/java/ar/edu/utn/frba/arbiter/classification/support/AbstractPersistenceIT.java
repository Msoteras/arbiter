package ar.edu.utn.frba.arbiter.classification.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real Postgres in a container (requires Docker). Singleton container on purpose: {@code @Container}
 * would stop it after each class and a restart changes its port, breaking cached Spring contexts.
 * ddl-auto is {@code update} only here because nobody runs {@code init-multitenant.sql} on the
 * container; production uses {@code validate}.
 */
@TestPropertySource(properties = {
        "arbiter.auth.jwt.secret=test-secret-at-least-32-bytes-long-for-hs256",
        "spring.jpa.hibernate.ddl-auto=update"
})
@Tag("it")
public abstract class AbstractPersistenceIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

    static {
        POSTGRES.start();
    }
}
