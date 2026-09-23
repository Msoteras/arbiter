package ar.edu.utn.frba.arbiter.classification.config.tenant;

import org.springframework.core.task.TaskDecorator;
import org.jspecify.annotations.NonNull;

/**
 * Carries the request's tenant into the async classification threads: {@link TenantContext} is a
 * ThreadLocal cleared as soon as the 202 is returned, so the task would otherwise fall back to
 * {@code arbiter_common}. Cleared in a finally so a pooled thread never leaks a schema to the next task.
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
