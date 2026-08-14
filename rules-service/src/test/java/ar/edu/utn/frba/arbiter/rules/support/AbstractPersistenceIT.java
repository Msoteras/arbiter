package ar.edu.utn.frba.arbiter.rules.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Same "singleton container" pattern as cases-service's {@code AbstractPersistenceIT}: no
 * {@code @Testcontainers}/{@code @Container}, because those annotations stop the container on each
 * class's {@code afterAll}, and with an inherited static field that kills it for the next class of
 * the same run. It starts once in the static block and lives until Ryuk cleans it up when the test
 * process ends.
 *
 * <p>Without this, a rules-service {@code @SpringBootTest} tried to start the context against the
 * Postgres in {@code application.yml} (default {@code localhost:5432}, user {@code arbiter}) and
 * failed with a password authentication error on any machine without that local database — the
 * module had no test infrastructure at all (D18).
 */
// SecurityConfig needs a real JWT_SECRET to start the context: the application.yml default is
// empty, and the HS256 key can't be built from that.
//
// ddl-auto is overridden to `update` only here: in production it's `validate` (the schema is
// defined by db/init-multitenant.sql), but these tests start against an empty container and nobody
// runs the script. The danger that motivates `validate` — Hibernate recreating the arbiter_common
// tables inside each tenant schema — doesn't apply: there are no tenant schemas here and everything
// lands in `public`. Same trade-off, and same debt, as cases-service: the only thing that would
// catch a drift between the entities and the real schema would be running init-multitenant.sql in
// the container.
@TestPropertySource(properties = {
        "arbiter.auth.jwt.secret=test-secret-at-least-32-bytes-long-for-hs256",
        "spring.jpa.hibernate.ddl-auto=update"
})
public abstract class AbstractPersistenceIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
