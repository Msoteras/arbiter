package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.HardRuleConfig;
import ar.edu.utn.frba.arbiter.rules.dto.HardRuleDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Referente-facing backoffice for the <b>coverage-scoped hard temporal rules</b> (waiting period,
 * report deadline, police-report deadline, events-per-year cap): which rules the insurer has
 * active for one coverage and, for the one that carries it, its threshold.
 *
 * <p>Policy-level rules (coverage window, arrears) aren't here — {@link InsurerHardRuleService}
 * handles those, scoped to the whole insurer instead of a coverage (see
 * {@code RuleType#insurerScoped()}'s javadoc for why).
 *
 * <p>Each rule is <b>one row</b> of {@code insurer_rule} per (branch, coverage), not one row with
 * every threshold inside. Two reasons: each evaluation needs its own {@code rule_result.rule_id}
 * so the audit trail says which rule was evaluated (Disposición SSN 2/2023), and the referente can
 * turn one off without touching the others.
 *
 * <p><b>The row is the switch, not the number.</b> The waiting period, report deadline and events
 * cap are terms of the contract and keep living in {@code coverage} columns, edited from the
 * Coverages tab; here it's only decided whether the rule runs. The exception is
 * {@code POLICE_DEADLINE}, which has no column and stores its threshold in the
 * {@code configuration} JSONB (see {@link HardRuleConfig}).
 *
 * <p>Every change leaves a snapshot in {@code insurer_rule_history}, same as Fast Track and the
 * exclusions. The tenant schema comes from the JWT: the referente only touches their own insurer.
 */
@Service
@RequiredArgsConstructor
public class HardRuleService {

    private static final Logger log = LoggerFactory.getLogger(HardRuleService.class);
    // Self-instantiated (Jackson 2), same as FastTrackRuleService: Spring Boot 4 auto-configures a
    // Jackson 3 (tools.jackson) ObjectMapper, so there's no com.fasterxml bean to inject.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<String> COVERAGE_SCOPED_RULE_NAMES = RuleType.coverageScoped().stream()
            .map(Enum::name)
            .toList();

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;
    private final BranchRepository branchRepository;

    /**
     * Every hard rule of the coverage, always all of them: the one with no row comes back
     * disabled. The panel shows the whole catalog — the referente can't turn on what they don't
     * see — and "no row" and "inactive row" mean the same thing to the engine: not evaluated.
     */
    @Transactional(readOnly = true)
    public List<HardRuleDto> get(Long branchId, Long coverageId) {
        Map<String, InsurerRule> configured = ruleRepository
                .findByBranch_IdAndCoverageIdAndRuleTypeIn(branchId, coverageId, COVERAGE_SCOPED_RULE_NAMES).stream()
                .collect(Collectors.toMap(InsurerRule::getRuleType, Function.identity(), (first, second) -> first));

        return RuleType.coverageScoped().stream()
                .map(type -> {
                    InsurerRule rule = configured.get(type.name());
                    return rule == null
                            ? HardRuleDto.disabled(type)
                            : new HardRuleDto(type, rule.isActive(), deserialize(rule.getConfiguration()).deadlineHours());
                })
                .toList();
    }

    /**
     * Saves whatever rules come in the request; the ones that don't come stay as they are (the
     * panel always sends the whole catalog, but a partial PUT shouldn't turn off what it doesn't
     * mention).
     */
    @Transactional
    public List<HardRuleDto> upsert(Long branchId, Long coverageId, List<HardRuleDto> requested, String actorEmail) {
        requested.forEach(rule -> upsertOne(branchId, coverageId, rule, actorEmail));
        return get(branchId, coverageId);
    }

    private void upsertOne(Long branchId, Long coverageId, HardRuleDto requested, String actorEmail) {
        if (requested.ruleType() == null) {
            throw new InvalidRuleConfigurationException("Missing the hard rule type");
        }
        if (!RuleType.coverageScoped().contains(requested.ruleType())) {
            throw new InvalidRuleConfigurationException(
                    "Type " + requested.ruleType() + " is not a coverage-scoped hard rule");
        }
        if (requested.ruleType() == RuleType.POLICE_DEADLINE
                && requested.enabled() && requested.deadlineHours() == null) {
            throw new InvalidRuleConfigurationException(
                    "The police-report deadline needs an hour threshold to be evaluable");
        }
        if (requested.deadlineHours() != null && requested.deadlineHours() < 0) {
            throw new InvalidRuleConfigurationException("The hour threshold can't be negative");
        }

        String json = serialize(configOf(requested));
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdAndCoverageIdAndRuleType(branchId, coverageId, requested.ruleType().name())
                .orElse(null);

        if (rule == null) {
            Branch branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new BranchNotFoundException(branchId));
            ruleRepository.save(InsurerRule.builder()
                    .active(requested.enabled())
                    .validFrom(now)
                    .name(defaultName(requested.ruleType(), coverageId))
                    .ruleType(requested.ruleType().name())
                    // A failed hard rule doesn't reject on its own (human-in-the-loop): it derives
                    // to the analyst with the reason. It blocks Fast Track, which is what the
                    // engine does decide on its own.
                    .effect("DERIVAR")
                    .blocksFastTrack(true)
                    .branch(branch)
                    .coverageId(coverageId)
                    .configuration(json)
                    .build());
            log.info("[HardRule] created — type={} branch={} coverage={} enabled={} by={}",
                    requested.ruleType(), branchId, coverageId, requested.enabled(), actorEmail);
            return;
        }

        // Snapshot of the version about to be overwritten, before touching it (append-only audit).
        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(rule.getConfiguration() == null ? "{}" : rule.getConfiguration())
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Hard rule " + requested.ruleType() + " updated by " + actorEmail)
                .insurerRule(rule)
                .changedBy(null)
                .build());

        rule.setActive(requested.enabled());
        rule.setConfiguration(json);
        rule.setValidFrom(now);
        ruleRepository.save(rule);
        log.info("[HardRule] updated — type={} branch={} coverage={} enabled={} by={}",
                requested.ruleType(), branchId, coverageId, requested.enabled(), actorEmail);
    }

    /** Only POLICE_DEADLINE carries its own threshold; for the rest the number lives on {@code coverage}. */
    private HardRuleConfig configOf(HardRuleDto requested) {
        return requested.ruleType() == RuleType.POLICE_DEADLINE
                ? new HardRuleConfig(requested.deadlineHours())
                : HardRuleConfig.empty();
    }

    /**
     * {@code insurer_rule.name} is NOT NULL and the referente reads it in the history view, so it
     * stays in Spanish — it's business-facing text, not an identifier (same treatment as the
     * audit-trail text elsewhere in the codebase).
     */
    private String defaultName(RuleType type, Long coverageId) {
        String label = switch (type) {
            case WAITING_PERIOD -> "Carencia de la cobertura";
            case REPORT_DEADLINE -> "Plazo de denuncia a la aseguradora";
            case POLICE_DEADLINE -> "Plazo de la denuncia policial";
            case MAX_EVENTS_YEAR -> "Tope de eventos por año";
            default -> type.name();
        };
        return label + " (cobertura " + coverageId + ")";
    }

    private HardRuleConfig deserialize(String json) {
        if (json == null || json.isBlank()) {
            return HardRuleConfig.empty();
        }
        try {
            return OBJECT_MAPPER.readValue(json, HardRuleConfig.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("Unreadable hard rule configuration: " + e.getOriginalMessage());
        }
    }

    private String serialize(HardRuleConfig config) {
        try {
            return OBJECT_MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("Could not serialize the hard rule configuration");
        }
    }
}
