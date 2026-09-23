package ar.edu.utn.frba.arbiter.auth.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Singleton container: no {@code @Testcontainers}/{@code @Container}, which would stop the
 * inherited static container after the first test class. Ryuk cleans it up when the JVM exits.
 *
 * <p>{@code ddl-auto=update} only here, against an empty container. It won't create the
 * {@code arbiter_common} schema on its own, hence the manual {@code CREATE SCHEMA} before Spring starts.
 */
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=update")
@Tag("it")
public abstract class AbstractPersistenceIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS arbiter_common");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
