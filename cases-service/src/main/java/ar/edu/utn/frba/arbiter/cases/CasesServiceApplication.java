package ar.edu.utn.frba.arbiter.cases;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CasesServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CasesServiceApplication.class, args);
    }
}
