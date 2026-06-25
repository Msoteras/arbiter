package ar.edu.utn.frba.arbiter.siniestros.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base para los tests que levantan el contexto de Spring: arranca un Postgres real
 * en un contenedor y lo cablea como datasource vía @ServiceConnection. Flyway corre
 * las migraciones contra el contenedor, así que el esquema también queda validado.
 *
 * Requiere Docker disponible al ejecutar los tests.
 */
@Testcontainers
public abstract class AbstractPersistenceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
}
