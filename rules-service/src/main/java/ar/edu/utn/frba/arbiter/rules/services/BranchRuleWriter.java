package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerRuleSnapshot;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Saves a branch-wide derivation rule ({@code coverage_id} null): created on the first save, left
 * alone when nothing changed, and otherwise updated after copying the previous version into
 * {@code insurer_rule_history}. Each derivation service validates and serializes its own
 * configuration; this is the part that would otherwise be written twice and drift.
 */
@Component
@RequiredArgsConstructor
class BranchRuleWriter {

    private static final Logger log = LoggerFactory.getLogger(BranchRuleWriter.class);

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;
    private final BranchRepository branchRepository;
    private final RuleAuthorResolver authorResolver;

    /** The rule as it stands after the save. */
    record Saved(boolean active, String configuration) {}

    /**
     * @param configuration the new configuration, or null to keep the stored one (turning the rule
     *                      off without retyping its value)
     * @return null when there is still no rule: off, with nothing to keep, the missing row already
     *         says "does not derive"
     */
    Saved upsert(Long branchId, String ruleType, String name, boolean enabled, String configuration,
                 String actorEmail) {
        Instant now = Instant.now();
        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(branchId, ruleType)
                .orElse(null);

        if (rule == null) {
            if (configuration == null) {
                return null;
            }
            Branch branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new BranchNotFoundException(branchId));
            ruleRepository.save(InsurerRule.builder()
                    .active(enabled)
                    .validFrom(now)
                    .createdBy(authorResolver.userIdOf(actorEmail))
                    .name(name)
                    .ruleType(ruleType)
                    .effect("DERIVAR")
                    .blocksFastTrack(false)
                    .branch(branch)
                    .coverageId(null)
                    .configuration(configuration)
                    .build());
            log.info("[{}] created — branch={} enabled={} configuration={} by={}",
                    ruleType, branchId, enabled, configuration, actorEmail);
            return new Saved(enabled, configuration);
        }

        String json = configuration == null ? rule.getConfiguration() : configuration;

        // A save that changes nothing leaves no audit entry.
        if (InsurerRuleSnapshot.unchanged(
                rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration(),
                enabled, rule.isBlocksFastTrack(), json)) {
            return new Saved(rule.isActive(), rule.getConfiguration());
        }

        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(InsurerRuleSnapshot.serialize(
                        rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration()))
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason(name + " actualizada por " + actorEmail)
                .insurerRule(rule)
                .changedBy(authorResolver.referentIdOf(actorEmail))
                .build());

        rule.setActive(enabled);
        rule.setConfiguration(json);
        rule.setValidFrom(now);
        // A seeded name may quote the original value, which the history would keep showing.
        rule.setName(name);
        ruleRepository.save(rule);
        log.info("[{}] updated — branch={} enabled={} configuration={} by={}",
                ruleType, branchId, enabled, json, actorEmail);
        return new Saved(enabled, json);
    }
}
