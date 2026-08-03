package ar.edu.utn.frba.arbiter.auth.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// ddl-auto se pisa a `update` solo acá: en producción es `validate` (el esquema lo define
// db/init-multitenant.sql). Ver el comentario largo en cases-service.
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=update")
@Testcontainers
public abstract class AbstractPersistenceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
}
