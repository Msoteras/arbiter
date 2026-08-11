package ar.edu.utn.frba.arbiter.reports.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Mismo patrón "singleton container" que los {@code AbstractPersistenceIT} de cases-service y
 * rules-service: sin {@code @Testcontainers}/{@code @Container}, porque esas anotaciones paran el
 * contenedor en el {@code afterAll} de cada clase y con un field estático heredado eso lo mata para
 * la clase siguiente de la misma corrida.
 *
 * <p>Sin esto, el {@code @SpringBootTest} de reports-service intentaba levantar el contexto contra
 * el Postgres del {@code application.yml} y fallaba en cualquier máquina sin esa base local (D18,
 * tercera instancia del mismo problema).
 *
 * <p>Cuando el módulo tenga controllers y services de verdad (hoy es solo la entidad {@code Metric}
 * y su repository — ver la brecha de la épica 9 en {@code docs/gap-historias-usuario.md}), esta
 * clase es la base de la que van a colgar sus tests de persistencia.
 */
// SecurityConfig necesita un JWT_SECRET real para levantar el contexto: el default del yml es vacío
// y ahí la key de HS256 no se puede construir.
//
// ddl-auto se pisa a `update` solo acá; en producción es `validate` (el esquema lo define
// db/init-multitenant.sql). Mismo trade-off y misma deuda que en los otros dos módulos: el
// contenedor arranca vacío y nadie corre el script, así que no habría tablas contra las cuales
// validar.
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
