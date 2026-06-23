package ar.edu.utn.frba.arbiter.siniestros;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@EnableRetry
@SpringBootApplication
public class SiniestrosServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SiniestrosServiceApplication.class, args);
    }
}
