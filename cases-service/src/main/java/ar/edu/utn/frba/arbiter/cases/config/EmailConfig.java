package ar.edu.utn.frba.arbiter.cases.config;

import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Same wiring as auth-service's: the adapter lives in common-lib but is built per module, since
 * each one reads its own configuration. With no API key it logs and skips instead of failing, so
 * a deployment without SendGrid still runs.
 */
@Configuration
public class EmailConfig {

    @Bean
    public SendGridAdapter sendGridAdapter(
            @Value("${arbiter.email.sendgrid-api-key:}") String apiKey,
            @Value("${arbiter.email.from-address:no-reply@arbiter.test}") String fromAddress,
            @Value("${arbiter.email.from-name:Arbiter}") String fromName) {
        return new SendGridAdapter(apiKey, fromAddress, fromName);
    }
}
