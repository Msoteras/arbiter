package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.CoverageExclusionConfig;
import ar.edu.utn.frba.arbiter.rules.dto.EvaluableRuleDto;
import ar.edu.utn.frba.arbiter.rules.dto.EvaluableRulesDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * System-to-system read of a coverage's <b>hard evaluable</b> rules for the classification engine
 * (no REFERENTE role, with a service token carrying the tenant) — same criterion as
 * {@code /internal/fast-track} and {@code /internal/rule-texts}. Keyed by coverage, which is what
 * the claim has at hand.
 *
 * <p>Today the only type is {@code COVERAGE_EXCLUSION}: the blacklist of claim causes the coverage
 * doesn't cover, persisted as an {@code insurer_rule} row with {@code configuration} JSONB. This
 * closes the DER's asymmetry, where no coverage ↔ claim cause relation exists (see
 * plan-reglas-evaluables.md §1.2). The rule's {@code id} travels to the engine because it's what
 * later goes into {@code rule_result.rule_id} (audit, SSN Disposition 2/2023).
 */
@Service
public class InternalEvaluableRuleService {

    private static final String COVERAGE_EXCLUSION = "COVERAGE_EXCLUSION";
    // Self-instantiated (Jackson 2), same as FastTrackRuleService: Spring Boot 4 autoconfigures a
    // Jackson 3 ObjectMapper (tools.jackson), so there's no com.fasterxml bean to inject.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InsurerRuleRepository ruleRepository;

    public InternalEvaluableRuleService(InsurerRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Transactional(readOnly = true)
    public EvaluableRulesDto getByCoverage(Long coverageId) {
        return ruleRepository.findFirstByCoverageIdAndRuleType(coverageId, COVERAGE_EXCLUSION)
                .filter(InsurerRule::isActive)
                .map(this::toDto)
                .map(dto -> new EvaluableRulesDto(List.of(dto)))
                .orElseGet(EvaluableRulesDto::empty);
    }

    private EvaluableRuleDto toDto(InsurerRule rule) {
        CoverageExclusionConfig config = deserialize(rule.getConfiguration());
        return new EvaluableRuleDto(
                rule.getId(),
                rule.getRuleType(),
                rule.getEffect(),
                rule.isBlocksFastTrack(),
                config.excludedClaimCauseIds());
    }

    private CoverageExclusionConfig deserialize(String json) {
        if (json == null || json.isBlank()) {
            return new CoverageExclusionConfig(List.of());
        }
        try {
            return OBJECT_MAPPER.readValue(json, CoverageExclusionConfig.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("Configuración de exclusión ilegible: " + e.getOriginalMessage());
        }
    }
}
