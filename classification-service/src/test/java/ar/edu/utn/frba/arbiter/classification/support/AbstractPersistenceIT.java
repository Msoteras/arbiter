package ar.edu.utn.frba.arbiter.classification.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for tests that boot the Spring context: starts a real Postgres in a
 * container and wires it as the datasource via @ServiceConnection. Hibernate's
 * ddl-auto=update creates the schema against the container, so it's validated too.
 *
 * Requires Docker available when running the tests.
 */
@Testcontainers
public abstract class AbstractPersistenceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");
}
