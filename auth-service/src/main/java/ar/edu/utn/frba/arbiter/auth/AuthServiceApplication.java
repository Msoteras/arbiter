package ar.edu.utn.frba.arbiter.auth;

import ar.edu.utn.frba.arbiter.auth.config.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// Explicit @EntityScan: the common-schema entities live in common-lib, outside this package.
@SpringBootApplication
@EntityScan({
        "ar.edu.utn.frba.arbiter.auth.models.entities",
        "ar.edu.utn.frba.arbiter.common.models.entities"
})
@EnableConfigurationProperties(AuthProperties.class)
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
