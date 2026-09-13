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
 * Once a day, re-reads every policy Arbiter has copied and realigns it with the insurer DB — the
 * <b>cron</b> half of decision #10 ("un cron —o consulta a demanda— trae los datos y los mapea a
 * las entidades propias de Arbiter"). The on-demand half already existed in
 * {@link PolicySynchronizer#importFromInsurer}; this one didn't, and without it a policy's local
 * copy is written once and never looked at again.
 *
 * <p>What that costs: the sum insured lives in two places — the company's {@code cobertura} and
 * Arbiter's {@code policy_coverage} — and they're read by different sides. Classification divides
 * by the company's (Fast Track's amount ratio, the {@code amount_ratio} scoring factor); the
 * analyst's screen and the wizard show Arbiter's. Let them drift and the number the analyst uses to
 * check a verdict stops being the number that produced it, which is precisely what the audit is
 * supposed to allow (Disposición SSN 2/2023).
 *
 * <p>Same shape as {@link LapseSweepScheduler} and {@link DeadlineSweepScheduler}: no request
 * behind it, so no JWT to resolve a tenant from, and the work is cross-tenant. One insurer failing
 * doesn't stop the rest — a company's DB being unreachable tonight is not a reason to leave the
 * other companies stale.
 *
 * <p>At 03:00 by default, off-peak on purpose: it walks every policy of every tenant and re-reads
 * each one from the insurer DB.
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
        // Always logged, zero included: "nothing changed last night" is the answer someone will
        // want when a sum insured looks wrong, and an absent line doesn't distinguish it from a
        // sweep that never ran.
        log.info("Policy resync in {}: {} policy(ies) read, {} realigned, {} row(s) updated",
                insurer.getSchemaName(), policies.size(), touched, changed);
    }
}
