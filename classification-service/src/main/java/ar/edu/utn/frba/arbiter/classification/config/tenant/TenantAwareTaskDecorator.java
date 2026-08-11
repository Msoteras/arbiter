package ar.edu.utn.frba.arbiter.classification.config.tenant;

import org.springframework.core.task.TaskDecorator;
import org.jspecify.annotations.NonNull;

/**
 * Carries the request's tenant into the async classification threads.
 *
 * <p>Without this the pipeline silently targets the wrong schema: {@link TenantContext} is a
 * ThreadLocal, {@code @Async("classificationExecutor")} runs on a different thread, and
 * {@code TenantResolvingFilter} clears the request thread's value as soon as the HTTP response
 * is returned — which happens immediately, since the endpoint answers 202 Accepted and lets the
 * classification run on (decision #4, asynchronous classification). The async task would then
 * fall back to {@code arbiter_common}, where the case tables do not exist.
 *
 * <p>Captured at submit time (still on the request thread) and cleared in a finally, so a pooled
 * thread never leaks one insurer's schema into the next task it picks up.
 */
public class TenantAwareTaskDecorator implements TaskDecorator {

    @Override
    public @NonNull Runnable decorate(@NonNull Runnable runnable) {
        String tenantSchema = TenantContext.get();
        return () -> {
            TenantContext.set(tenantSchema);
            try {
                runnable.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}
