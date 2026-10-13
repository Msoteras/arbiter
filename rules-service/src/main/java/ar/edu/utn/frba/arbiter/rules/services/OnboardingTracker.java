package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import ar.edu.utn.frba.arbiter.rules.models.entities.OnboardingStepState;
import ar.edu.utn.frba.arbiter.rules.models.repositories.OnboardingStepStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Records that a step's section was saved. After a checklist restart, that save is what completes
 * the step. Best-effort: a failure here is logged and never undoes the save that triggered it.
 */
@Component
public class OnboardingTracker {

    private static final Logger log = LoggerFactory.getLogger(OnboardingTracker.class);

    private final OnboardingStepStateRepository stateRepository;
    private final TransactionTemplate newTransaction;

    public OnboardingTracker(OnboardingStepStateRepository stateRepository, PlatformTransactionManager txManager) {
        this.stateRepository = stateRepository;
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Inside a transaction, waits for its commit: a save that rolls back didn't happen. Runs in a
     * transaction of its own, so a failure can't mark the save's transaction for rollback.
     */
    public void touch(OnboardingStep step, Long branchId) {
        String key = step.key(branchId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(key);
                }
            });
        } else {
            write(key);
        }
    }

    private void write(String key) {
        try {
            newTransaction.executeWithoutResult(status -> {
                OnboardingStepState state = stateRepository.findById(key).orElseGet(() -> new OnboardingStepState(key));
                state.setTouchedAt(Instant.now());
                stateRepository.save(state);
            });
        } catch (Exception e) {
            log.warn("[OnboardingTracker] Could not record a save of step {}: {}", key, e.getMessage());
        }
    }
}
