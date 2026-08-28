package ar.edu.utn.frba.arbiter.reports.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Same "singleton container" pattern as the {@code AbstractPersistenceIT} of cases-service and
 * rules-service: no {@code @Testcontainers}/{@code @Container}, because those annotations stop the
 * container on each class's {@code afterAll}, and with an inherited static field that kills it for
 * the next class of the same run.
 *
 * <p>Without this, reports-service's {@code @SpringBootTest} tried to start the context against the
 * Postgres in {@code application.yml} and failed on any machine without that local database (D18,
 * third instance of the same problem).
 *
 * <p>Once the module has real controllers and services (today it's only the {@code Metric} entity
 * and its repository), this class is the base their persistence tests will hang off.
 */
// SecurityConfig needs a real JWT_SECRET to start the context: the yml default is empty and the
// HS256 key can't be built from that.
//
// ddl-auto is overridden to `update` only here; in production it's `validate` (the schema is
// defined by db/init-multitenant.sql). Same trade-off and same debt as in the other two modules:
// the container starts empty and nobody runs the script, so there'd be no tables to validate
// against.
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
