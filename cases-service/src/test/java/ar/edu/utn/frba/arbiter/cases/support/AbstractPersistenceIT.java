package ar.edu.utn.frba.arbiter.cases.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
// db/init-multitenant.sql), pero estos tests arrancan contra un contenedor vacío y nadie corre el
// script, así que sin esto no habría tablas contra las cuales validar. El peligro que motiva
// `validate` —que Hibernate recree las tablas de arbiter_common adentro de cada esquema de
// tenant— no aplica: acá no hay esquemas de tenant y todo cae en `public`.
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
    }
}
