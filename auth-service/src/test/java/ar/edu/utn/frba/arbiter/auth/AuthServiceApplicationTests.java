package ar.edu.utn.frba.arbiter.auth;

import ar.edu.utn.frba.arbiter.auth.support.AbstractPersistenceIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Dummy Auth0 domain: the AuthAPI bean needs a parseable URL even though it's never called.
@SpringBootTest(properties = "arbiter.auth.auth0.domain=example.auth0.com")
class AuthServiceApplicationTests extends AbstractPersistenceIT {

    @Test
    void contextLoads() {
    }

}
