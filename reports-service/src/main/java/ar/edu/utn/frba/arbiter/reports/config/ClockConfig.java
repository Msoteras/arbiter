package ar.edu.utn.frba.arbiter.reports.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injectable {@link Clock}, same as cases-service's. Its zone is also the zone the report
 * reads calendar days in: the Dockerfile pins the JVM to America/Argentina/Buenos_Aires, so "31/08"
 * means the insurer's 31/08, not UTC's.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
