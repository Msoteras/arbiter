package ar.edu.utn.frba.arbiter.classification.support;

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
 * Patrón "singleton container" (recomendado por Testcontainers para compartir un contenedor entre
 * varias clases de test): sin {@code @Testcontainers}/{@code @Container}, porque esas anotaciones
 * paran el contenedor en el {@code afterAll} de cada clase — con un field estático heredado, eso
 * mata el contenedor para la siguiente clase que lo use en la misma corrida (y peor: un reinicio le
 * cambia el puerto, dejando contextos de Spring ya cacheados apuntando a un puerto muerto). Arrancado
 * una sola vez en el bloque estático (se ejecuta una vez por JVM, al cargar esta clase) y vive hasta
 * que Ryuk lo limpia al terminar el proceso de test. Mismo patrón que `cases-service`.
 */
// SecurityConfig requiere un JWT_SECRET real para levantar el contexto (H0003).
//
// ddl-auto se pisa a `update` solo acá: en producción es `validate` (el esquema lo define
// db/init-multitenant.sql). Ver el comentario largo en cases-service.
@TestPropertySource(properties = {
        "arbiter.auth.jwt.secret=test-secret-at-least-32-bytes-long-for-hs256",
        "spring.jpa.hibernate.ddl-auto=update"
})
public abstract class AbstractPersistenceIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

    static {
        POSTGRES.start();
    }
}
