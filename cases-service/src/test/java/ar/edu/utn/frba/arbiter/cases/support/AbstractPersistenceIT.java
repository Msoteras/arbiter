package ar.edu.utn.frba.arbiter.cases.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Patrón "singleton container" (recomendado por Testcontainers para compartir un contenedor entre
 * varias clases de test): sin {@code @Testcontainers}/{@code @Container}, porque esas anotaciones
 * paran el contenedor en el {@code afterAll} de cada clase — con un field estático heredado, eso
 * mata el contenedor para la siguiente clase que lo use en la misma corrida. Arrancado una sola vez
 * en el bloque estático (se ejecuta una vez por JVM, al cargar esta clase) y vive hasta que Ryuk lo
 * limpia al terminar el proceso de test.
 */
// SecurityConfig requiere un JWT_SECRET real para levantar el contexto (H0003).
//
// ddl-auto se pisa a `update` solo acá: en producción es `validate` (el esquema lo define
// db/init-multitenant.sql). Las entidades de common-lib son schema-qualified
// (`arbiter_common`) — no caen en `public` como decía este comentario antes, Hibernate arma la
// DDL como `create table arbiter_common.branch(...)` literal, y como `update` no crea esquemas
// solo (`hibernate.hbm2ddl.create_namespaces` es false por default) hacía falta el CREATE SCHEMA
// manual de acá abajo antes de arrancar el contexto. Sin él, cualquier IT que levante el
// contexto completo rompe con "schema arbiter_common does not exist" contra un contenedor vacío.
// Pendiente: hacer que el contenedor corra init-multitenant.sql, que es lo único que detectaría
// un desfasaje entre las entidades y el esquema real.
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
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
