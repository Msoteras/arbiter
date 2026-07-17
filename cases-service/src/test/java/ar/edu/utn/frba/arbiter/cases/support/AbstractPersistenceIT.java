package ar.edu.utn.frba.arbiter.cases.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Patrón "singleton container" (recomendado por Testcontainers para compartir un contenedor entre
 * varias clases de test): sin {@code @Testcontainers}/{@code @Container}, porque esas anotaciones
 * paran el contenedor en el {@code afterAll} de cada clase — con un field estático heredado, eso
 * mata el contenedor para la siguiente clase que lo use en la misma corrida. Arrancado una sola vez
 * en el bloque estático (se ejecuta una vez por JVM, al cargar esta clase) y vive hasta que Ryuk lo
 * limpia al terminar el proceso de test.
 */
public abstract class AbstractPersistenceIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
