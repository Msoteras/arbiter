package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageExclusionConfig;
import ar.edu.utn.frba.arbiter.rules.dto.EvaluableRuleDto;
import ar.edu.utn.frba.arbiter.rules.dto.EvaluableRulesDto;
import ar.edu.utn.frba.arbiter.rules.dto.HardRuleConfig;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * System-to-system read of a coverage's <b>hard evaluable rules</b> for the classification engine
 * (no REFERENTE role, service token carrying the tenant) — same criterion as
 * {@code /internal/fast-track} and {@code /internal/rule-texts}. Keyed by coverage, which is what
 * the claim has at hand — except the insurer-wide rules below, which need no key at all.
 *
 * <p>Returns three families in the same flat list — {@code TemporalRuleEvaluator} treats them all
 * the same regardless of scope, only the storage differs:
 * <ul>
 *   <li>{@code COVERAGE_EXCLUSION}: the blacklist of hechos generadores the coverage doesn't
 *       cover. Closes the DER's asymmetry, where no coverage ↔ hecho generador relation
 *       exists.</li>
 *   <li>The active coverage-scoped hard temporal rules ({@link RuleType#coverageScoped()}):
 *       waiting period, deadlines, events cap. They travel with almost no parameters because the
 *       threshold belongs to the coverage; what the row contributes is that the rule is active —
 *       without it the engine doesn't evaluate it — and the {@code id} {@code rule_result} is
 *       going to point at.</li>
 *   <li>The active insurer-wide hard temporal rules ({@link RuleType#insurerScoped()}): coverage
 *       window, arrears. Same shape, but the row has no branch/coverage — one per insurer, not
 *       one per coverage.</li>
 * </ul>
 *
 * <p>Only <b>active</b> rules are returned: a rule the referente turned off is, to the engine, a
 * rule that doesn't exist.
 */
@Service
public class InternalEvaluableRuleService {

    // Self-instantiated (Jackson 2), same as FastTrackRuleService: Spring Boot 4 auto-configures a
    // Jackson 3 (tools.jackson) ObjectMapper, so there's no com.fasterxml bean to inject.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<String> COVERAGE_SCOPED_RULE_NAMES = RuleType.coverageScoped().stream()
            .map(Enum::name)
            .toList();

    private static final List<String> INSURER_SCOPED_RULE_NAMES = RuleType.insurerScoped().stream()
            .map(Enum::name)
            .toList();

    private final InsurerRuleRepository ruleRepository;

    public InternalEvaluableRuleService(InsurerRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Transactional(readOnly = true)
    public EvaluableRulesDto getByCoverage(Long coverageId) {
        List<EvaluableRuleDto> rules = new ArrayList<>();

        ruleRepository.findFirstByCoverageIdAndRuleType(coverageId, RuleType.COVERAGE_EXCLUSION.name())
                .filter(InsurerRule::isActive)
                .map(this::toExclusionDto)
                .ifPresent(rules::add);

        ruleRepository.findByCoverageIdAndRuleTypeIn(coverageId, COVERAGE_SCOPED_RULE_NAMES).stream()
                .filter(InsurerRule::isActive)
                .map(this::toTemporalDto)
                .forEach(rules::add);

        ruleRepository.findByBranch_IdIsNullAndCoverageIdIsNullAndRuleTypeIn(INSURER_SCOPED_RULE_NAMES).stream()
                .filter(InsurerRule::isActive)
                .map(this::toTemporalDto)
                .forEach(rules::add);

        return rules.isEmpty() ? EvaluableRulesDto.empty() : new EvaluableRulesDto(rules);
    }

    private EvaluableRuleDto toExclusionDto(InsurerRule rule) {
        return new EvaluableRuleDto(
                rule.getId(),
                rule.getRuleType(),
                rule.getEffect(),
                rule.isBlocksFastTrack(),
                deserializeExclusion(rule.getConfiguration()).excludedClaimCauseIds(),
                null);
    }

    private EvaluableRuleDto toTemporalDto(InsurerRule rule) {
        return new EvaluableRuleDto(
                rule.getId(),
                rule.getRuleType(),
                rule.getEffect(),
                rule.isBlocksFastTrack(),
                null,
                deserializeHardRule(rule.getConfiguration()).deadlineHours());
    }

    private CoverageExclusionConfig deserializeExclusion(String json) {
        if (json == null || json.isBlank()) {
            return new CoverageExclusionConfig(List.of());
        }
        try {
            return OBJECT_MAPPER.readValue(json, CoverageExclusionConfig.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("Unreadable exclusion configuration: " + e.getOriginalMessage());
        }
    }

    /**
     * An unreadable configuration here does <b>not</b> sink the read: the rule travels without a
     * threshold and the engine discards it on its own. Different from the exclusion, where the
     * JSON <i>is</i> the rule; for the temporal ones the JSON is empty on purpose most of the time.
     */
    private HardRuleConfig deserializeHardRule(String json) {
        if (json == null || json.isBlank()) {
            return HardRuleConfig.empty();
        }
        try {
            return OBJECT_MAPPER.readValue(json, HardRuleConfig.class);
        } catch (JsonProcessingException e) {
            return HardRuleConfig.empty();
        }
    }
}
