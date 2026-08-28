package ar.edu.utn.frba.arbiter.rules;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

/**
 * {@code @EntityScan} is explicit because the common-schema entities (branch, claim_cause,
 * case_status, insurer) live in common-lib, outside this module's package — the default
 * scan only covers the application class's own package.
 */
@SpringBootApplication
@EntityScan({
        "ar.edu.utn.frba.arbiter.rules.models.entities",
        "ar.edu.utn.frba.arbiter.common.models.entities"
})
public class RulesServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RulesServiceApplication.class, args);
    }
}
