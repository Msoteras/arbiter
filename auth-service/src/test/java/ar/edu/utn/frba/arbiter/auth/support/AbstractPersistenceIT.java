package ar.edu.utn.frba.arbiter.auth.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * "Singleton container" pattern (same as cases-service/classification-service/rules-service):
 * no {@code @Testcontainers}/{@code @Container}, because those stop the container on each
 * class's {@code afterAll} — with an inherited static field that kills it for the next test
 * class in the same run. Started once in the static block and alive until Ryuk cleans it up
 * when the test process ends.
 */
// ddl-auto se pisa a `update` solo acá: en producción es `validate` (el esquema lo define
// db/init-multitenant.sql). Las entidades de common-lib son schema-qualified
// (`arbiter_common`), así que `update` necesita que el esquema ya exista antes de crear las
// tablas ahí adentro — Hibernate no lo crea solo (`hibernate.hbm2ddl.create_namespaces` es
// false por default). De ahí el CREATE SCHEMA manual acá abajo, antes de que arranque el
// contexto de Spring.
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=update")
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
