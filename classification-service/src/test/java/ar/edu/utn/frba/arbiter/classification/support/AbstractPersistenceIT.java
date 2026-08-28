package ar.edu.utn.frba.arbiter.classification.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that boot the Spring context: starts a real Postgres in a
 * container and wires it as the datasource via @ServiceConnection. Hibernate's
 * ddl-auto=update creates the schema against the container, so it's validated too.
 *
 * Requires Docker available when running the tests.
 *
 * "Singleton container" pattern (recommended by Testcontainers to share one container across
 * several test classes): no {@code @Testcontainers}/{@code @Container}, because those annotations
 * stop the container on each class's {@code afterAll} — with an inherited static field that kills
 * it for the next class using it in the same run (and worse: a restart changes its port, leaving
 * already-cached Spring contexts pointing at a dead one). Started once in the static block (which
 * runs once per JVM, when this class loads) and alive until Ryuk cleans it up when the test process
 * ends. Same pattern as `cases-service`.
 */
// SecurityConfig needs a real JWT_SECRET to start the context (H0003).
//
// ddl-auto se pisa a `update` solo acá: en producción es `validate` (el esquema lo define
// db/init-multitenant.sql). Ver el comentario largo en cases-service.
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
