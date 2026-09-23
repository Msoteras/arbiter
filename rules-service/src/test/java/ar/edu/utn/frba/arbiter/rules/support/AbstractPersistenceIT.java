package ar.edu.utn.frba.arbiter.rules.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Singleton container: no {@code @Testcontainers}/{@code @Container}, because those stop the
 * container in each class's {@code afterAll} and an inherited static field would die for the next
 * class. It starts once in the static block and Ryuk cleans it up when the process ends.
 */
// SecurityConfig can't build the HS256 key from the empty default JWT secret.
// ddl-auto is `update` only here, since nobody runs db/init-multitenant.sql in the container.
// common-lib's entities are qualified with `arbiter_common` and `update` doesn't create schemas,
// hence the CREATE SCHEMA before the context boots.
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
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS arbiter_common");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
