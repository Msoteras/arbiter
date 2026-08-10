package ar.edu.utn.frba.arbiter.rules.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Mismo patrón "singleton container" que el {@code AbstractPersistenceIT} de cases-service: sin
 * {@code @Testcontainers}/{@code @Container}, porque esas anotaciones paran el contenedor en el
 * {@code afterAll} de cada clase y con un field estático heredado eso lo mata para la clase
 * siguiente de la misma corrida. Se arranca una vez en el bloque estático y vive hasta que Ryuk lo
 * limpia al terminar el proceso de test.
 *
 * <p>Sin esto, un {@code @SpringBootTest} de rules-service intentaba levantar el contexto contra el
 * Postgres del {@code application.yml} (default {@code localhost:5432}, usuario {@code arbiter}) y
 * fallaba con {@code FATAL: la autentificación password falló} en cualquier máquina que no tuviera
 * esa base local — el módulo no tenía ninguna infraestructura de test (D18).
 */
// SecurityConfig necesita un JWT_SECRET real para levantar el contexto: en application.yml el
// default es vacío, y ahí la key de HS256 no se puede construir.
//
// ddl-auto se pisa a `update` solo acá: en producción es `validate` (el esquema lo define
// db/init-multitenant.sql), pero estos tests arrancan contra un contenedor vacío y nadie corre el
// script. El peligro que motiva `validate` —que Hibernate recree las tablas de arbiter_common
// adentro de cada esquema de tenant— no aplica: acá no hay esquemas de tenant y todo cae en
// `public`. Mismo trade-off, y misma deuda, que en cases-service: lo único que detectaría un
// desfasaje entre las entidades y el esquema real sería correr init-multitenant.sql en el contenedor.
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
