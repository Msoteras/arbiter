package ar.edu.utn.frba.arbiter.cases.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Singleton-container pattern: no {@code @Testcontainers}/{@code @Container}, because those stop
 * the container in each class's {@code afterAll} and would kill it for the next class in the same
 * run. It starts once per JVM in the static block and Ryuk removes it when the run ends.
 *
 * <p>ddl-auto is {@code update} only here (production validates against
 * {@code db/init-multitenant.sql}). {@code update} doesn't create schemas, so
 * {@code arbiter_common} is created by hand before the context starts.
 *
 * <p>Tagged {@code it}: subclasses are excluded from {@code mvn test} and run with
 * {@code mvn verify -Pit}.
 */
@Tag("it")
@TestPropertySource(properties = {
        "arbiter.auth.jwt.secret=test-secret-at-least-32-bytes-long-for-hs256",
        "spring.jpa.hibernate.ddl-auto=update"
})
public abstract class AbstractPersistenceIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS arbiter_common");
            // Case search uses unaccent() (CaseSpecifications.freeText); in production
            // db/init-multitenant.sql creates it.
            stmt.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
