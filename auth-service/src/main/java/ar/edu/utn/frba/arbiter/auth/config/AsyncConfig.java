package ar.edu.utn.frba.arbiter.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Turns on {@code @Async} for the bulk insured provisioning run, which cannot hold the referente's
 * request open while it walks the company's book and paces out the invitation mails.
 *
 * <p>No explicit executor: {@code spring.threads.virtual.enabled} is on, so Boot's default is
 * already backed by virtual threads — which is what makes the deliberate sleep between mails cheap
 * rather than a platform thread parked doing nothing.
 *
 * <p>Anything dispatched this way loses the request's {@code ThreadLocal}s, {@code TenantContext}
 * among them: the tenant has to be handed over as an argument and set again on the new thread.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
