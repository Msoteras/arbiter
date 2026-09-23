package ar.edu.utn.frba.arbiter.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables {@code @Async} for bulk insured provisioning. No explicit executor: virtual threads are
 * on, so the pacing sleep between mails is cheap.
 *
 * <p>Async work loses the request's {@code ThreadLocal}s, {@code TenantContext} included: pass the
 * tenant as an argument and set it again on the new thread.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
