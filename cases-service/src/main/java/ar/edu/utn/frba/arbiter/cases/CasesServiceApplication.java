package ar.edu.utn.frba.arbiter.cases;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EntityScan} is explicit because {@code Insurer} lives in common-lib, outside this
 * module's package — {@link ar.edu.utn.frba.arbiter.cases.services.ClassificationRefreshScheduler}
 * reads it to know which tenant schemas to sweep.
 */
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
