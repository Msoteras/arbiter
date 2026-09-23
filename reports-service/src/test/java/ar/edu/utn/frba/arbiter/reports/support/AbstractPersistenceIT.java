package ar.edu.utn.frba.arbiter.reports.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Singleton container: no {@code @Testcontainers}/{@code @Container}, because those stop the container
 * in each class's {@code afterAll}, killing the inherited static one for the next class of the run.
 *
 * <p>The schema is flat (everything in {@code public}, no {@code arbiter_common}); tests that read other
 * modules' tables create them by hand (see {@link CaseTables}).
 */
// JWT_SECRET: the yml default is empty and the HS256 key can't be built from it. ddl-auto is `update`
// only here: the container starts empty and nobody runs db/init-multitenant.sql.
@TestPropertySource(properties = {
        "arbiter.auth.jwt.secret=test-secret-at-least-32-bytes-long-for-hs256",
        "spring.jpa.hibernate.ddl-auto=update"
})
@Tag("it")
public abstract class AbstractPersistenceIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
