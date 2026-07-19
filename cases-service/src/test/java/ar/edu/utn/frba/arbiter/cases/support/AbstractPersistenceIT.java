package ar.edu.utn.frba.arbiter.cases.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
// SecurityConfig requiere un JWT_SECRET real para levantar el contexto (H0003).
@TestPropertySource(properties = "arbiter.auth.jwt.secret=test-secret-at-least-32-bytes-long-for-hs256")
public abstract class AbstractPersistenceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
}
