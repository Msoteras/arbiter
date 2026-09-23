package ar.edu.utn.frba.arbiter.cases;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @EntityScan} is explicit because the common-schema entities live in common-lib, outside this package. */
@EnableScheduling
@SpringBootApplication
@EntityScan({
        "ar.edu.utn.frba.arbiter.cases.models.entities",
        "ar.edu.utn.frba.arbiter.common.models.entities"
})
public class CasesServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CasesServiceApplication.class, args);
    }
}
