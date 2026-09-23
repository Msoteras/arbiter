package ar.edu.utn.frba.arbiter.classification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.retry.annotation.EnableRetry;

/** {@code @EntityScan} includes common-lib's entities (e.g. {@code ClaimCause}), outside this module's package. */
@EnableRetry
@SpringBootApplication
@EntityScan({
        "ar.edu.utn.frba.arbiter.classification.models.entities",
        "ar.edu.utn.frba.arbiter.common.models.entities"
})
public class ClassificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClassificationServiceApplication.class, args);
    }
}
