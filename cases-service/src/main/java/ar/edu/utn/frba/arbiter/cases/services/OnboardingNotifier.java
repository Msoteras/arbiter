package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reports saves of the checklist steps cases-service owns (coverages, providers, settlement
 * authorities) to rules-service. Waits for the commit: a save that rolls back didn't happen, and
 * the HTTP call must not hold the transaction open.
 */
@Component
@RequiredArgsConstructor
public class OnboardingNotifier {

    private final RulesServiceClient rulesServiceClient;

    public void saved(OnboardingStep step, Long branchId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    rulesServiceClient.recordOnboardingSave(step, branchId);
                }
            });
        } else {
            rulesServiceClient.recordOnboardingSave(step, branchId);
        }
    }
}
