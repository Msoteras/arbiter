package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Once a day, re-reads every locally snapshotted policy and realigns it with the insurer DB.
 * Classification reads the sum insured from the insurer's DB while the analyst's screen shows the
 * local copy; if they drift, the analyst can no longer check the number that produced a verdict.
 *
 * <p>Cross-tenant with no request behind it; one insurer failing doesn't stop the rest. Runs
 * off-peak by default because it re-reads every policy of every tenant.
 */
@Component
@RequiredArgsConstructor
public class PolicyResyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PolicyResyncScheduler.class);

    private final PolicyRepository policyRepository;
    private final InsurerRepository insurerRepository;
    private final PolicySynchronizer policySynchronizer;

    @Scheduled(cron = "${arbiter.policy-resync.cron:0 0 3 * * *}")
    public void resyncPolicies() {
        for (Insurer insurer : insurerRepository.findByActiveTrue()) {
            try {
                TenantContext.set(insurer.getSchemaName());
                resyncCurrentTenant(insurer);
            } catch (Exception e) {
                log.warn("Policy resync failed for insurer {} ({}): {}",
                        insurer.getName(), insurer.getSchemaName(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void resyncCurrentTenant(Insurer insurer) {
        List<Policy> policies = policyRepository.findAll();
        if (policies.isEmpty()) {
            return;
        }
        int changed = 0;
        int touched = 0;
        for (Policy policy : policies) {
            try {
                int changes = policySynchronizer.resync(policy);
                if (changes > 0) {
                    changed += changes;
                    touched++;
                }
            } catch (Exception e) {
                // One policy the company answers badly can't cost the rest their refresh.
                log.warn("Policy resync failed for {} in {}: {}",
                        policy.getExternalPolicyNumber(), insurer.getSchemaName(), e.getMessage());
            }
        }
        // Logged even when nothing changed, so it's distinguishable from a sweep that never ran.
        log.info("Policy resync in {}: {} policy(ies) read, {} realigned, {} row(s) updated",
                insurer.getSchemaName(), policies.size(), touched, changed);
    }
}
