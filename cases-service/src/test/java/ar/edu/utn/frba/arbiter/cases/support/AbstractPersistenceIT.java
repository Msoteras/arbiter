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
 * Singleton container: {@code @Testcontainers} would stop it after each class. {@code update} only
 * here, and it doesn't create schemas, so {@code arbiter_common} is created by hand. Tagged
 * {@code it}: runs with {@code mvn verify -Pit}, not {@code mvn test}.
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
            // Used by CaseSpecifications.freeText; in production init-multitenant.sql creates it.
            stmt.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
