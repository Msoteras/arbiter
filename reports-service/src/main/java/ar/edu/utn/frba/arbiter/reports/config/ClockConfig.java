package ar.edu.utn.frba.arbiter.reports.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Its zone is also the zone reports read calendar days in: the Dockerfile pins the JVM to
 * America/Argentina/Buenos_Aires, so a day boundary is the insurer's, not UTC's.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
