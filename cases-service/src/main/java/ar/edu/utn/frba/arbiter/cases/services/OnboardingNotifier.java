package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reports saves of the checklist steps cases-service owns (coverages, providers, settlement
 * authorities) to rules-service. Waits for the commit: a save that rolls back didn't happen, and
 * the HTTP call must not hold the transaction open. Sent from a virtual thread: a sleeping
 * rules-service takes up to half a minute to answer, and the referent's save is already done.
 */
@Component
@RequiredArgsConstructor
public class OnboardingNotifier {

    private final RulesServiceClient rulesServiceClient;

    public void saved(OnboardingStep step, Long branchId) {
        // Read here: TenantContext is a ThreadLocal, and the send runs on another thread.
        String tenant = TenantContext.get();
        Runnable send = () -> Thread.ofVirtual().name("onboarding-touch")
                .start(() -> rulesServiceClient.recordOnboardingSave(step, branchId, tenant));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
