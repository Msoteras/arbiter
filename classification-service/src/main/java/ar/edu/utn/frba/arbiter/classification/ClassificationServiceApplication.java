package ar.edu.utn.frba.arbiter.classification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.retry.annotation.EnableRetry;

/**
 * {@code @EntityScan} is explicit because {@code ClaimCause} lives in common-lib, outside this
 * module's package — {@link ar.edu.utn.frba.arbiter.classification.services.ClassificationOrchestrator}
 * reads the branch's claim cause catalog to check the account against the declared cause. Without
 * it Hibernate never registers the entity and the repository fails at startup with
 * "Not a managed type".
 */
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
