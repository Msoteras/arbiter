package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Records that a step's section was saved. After a checklist restart, that save is what completes
 * the step. Best-effort: a failure here is logged and never undoes the save that triggered it.
 */
@Component
public class OnboardingTracker {

    private static final Logger log = LoggerFactory.getLogger(OnboardingTracker.class);

    private static final String UPSERT = """
            INSERT INTO onboarding_step (step_key, skipped, touched_at) VALUES (?, false, ?)
            ON CONFLICT (step_key) DO UPDATE SET touched_at = EXCLUDED.touched_at""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public OnboardingTracker(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(txManager);
    }

    /**
     * Inside a transaction, waits for its commit: a save that rolls back didn't happen. Runs behind
     * a savepoint, so a failure can't abort the save's transaction.
     */
    public void touch(OnboardingStep step, Long branchId) {
        String key = step.key(branchId);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    bestEffort(key, () -> write(key));
                }
            });
        } else {
            bestEffort(key, () -> transaction.executeWithoutResult(status -> write(key)));
        }
    }

    private void write(String key) {
        jdbc.execute("SAVEPOINT onboarding_touch");
        try {
            jdbc.update(UPSERT, key, Timestamp.from(Instant.now()));
        } catch (RuntimeException e) {
            jdbc.execute("ROLLBACK TO SAVEPOINT onboarding_touch");
            throw e;
        }
    }

    private static void bestEffort(String key, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.warn("[OnboardingTracker] Could not record a save of step {}: {}", key, e.getMessage());
        }
    }
}
