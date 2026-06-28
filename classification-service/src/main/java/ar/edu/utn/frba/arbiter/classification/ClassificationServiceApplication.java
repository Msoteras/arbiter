package ar.edu.utn.frba.arbiter.classification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@EnableRetry
@SpringBootApplication
public class ClassificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClassificationServiceApplication.class, args);
    }
}
