package ar.edu.utn.frba.arbiter.cases.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Injectable so time-dependent logic (the deadline sweep) can be tested with a fixed "today". */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
