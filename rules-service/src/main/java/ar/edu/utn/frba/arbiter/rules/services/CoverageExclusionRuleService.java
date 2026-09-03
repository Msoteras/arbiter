package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.CatalogOption;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageExclusionConfig;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageExclusionResponse;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerRuleSnapshot;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
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

/**
 * Referente backoffice: which claim causes a coverage does NOT cover (the "hard exclusion" the
 * engine evaluates in code and audits in {@code rule_result}, unlike the text exclusions that only
 * reach the prompt). Persisted as a {@code COVERAGE_EXCLUSION} row of {@link InsurerRule} with
 * {@code configuration} JSONB = {@link CoverageExclusionConfig} (a list of {@code claim_cause} ids).
 * Every change leaves a snapshot in {@code insurer_rule_history} (append-only audit), same as Fast
 * Track. The tenant schema comes from the JWT, so the referente only sees and edits their insurer.
 */
@Service
@RequiredArgsConstructor
public class CoverageExclusionRuleService {

    private static final Logger log = LoggerFactory.getLogger(CoverageExclusionRuleService.class);
    // Self-instantiated (Jackson 2), same as FastTrackRuleService: Spring Boot 4 autoconfigures a
    // Jackson 3 ObjectMapper (tools.jackson), so there's no com.fasterxml bean to inject.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;
    private final BranchRepository branchRepository;
    private final ClaimCauseRepository claimCauseRepository;

    /** The branch's claim causes, to populate the exclusion picker (id + name). */
    @Transactional(readOnly = true)
    public List<CatalogOption> listClaimCauses(Long branchId) {
        return claimCauseRepository.findByBranch_IdOrderByNameAsc(branchId).stream()
                .map(cause -> new CatalogOption(cause.getId(), cause.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CoverageExclusionConfig get(Long coverageId) {
        return ruleRepository.findFirstByCoverageIdAndRuleType(coverageId, RuleType.COVERAGE_EXCLUSION.name())
                .map(rule -> deserialize(rule.getConfiguration()))
                .orElseGet(() -> new CoverageExclusionConfig(List.of()));
    }

    @Transactional
    public CoverageExclusionResponse upsert(Long branchId, Long coverageId, CoverageExclusionConfig config, String actorEmail) {
        String json = serialize(config);
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdAndCoverageIdAndRuleType(branchId, coverageId, RuleType.COVERAGE_EXCLUSION.name())
                .orElse(null);

        if (rule == null) {
            Branch branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new BranchNotFoundException(branchId));
            rule = InsurerRule.builder()
                    .active(true)
                    .validFrom(now)
                    .name("Exclusiones de cobertura " + coverageId)
                    .ruleType(RuleType.COVERAGE_EXCLUSION.name())
                    .effect("RECHAZAR")
                    // A hard exclusion makes Fast Track irrelevant (the engine evaluates it first).
                    .blocksFastTrack(true)
                    .branch(branch)
                    .coverageId(coverageId)
                    .configuration(json)
                    .build();
            rule = ruleRepository.save(rule);
            log.info("[CoverageExclusion] created — branch={} coverage={} by={}", branchId, coverageId, actorEmail);
            return new CoverageExclusionResponse(rule.getId(), branchId, coverageId, config.excludedClaimCauseIds());
        }

        // Snapshot of the version about to be overwritten, before touching it (append-only audit).
        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(InsurerRuleSnapshot.serialize(
                        rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration()))
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Exclusiones de cobertura actualizadas por " + actorEmail)
                .insurerRule(rule)
                .changedBy(null)
                .build());

        rule.setConfiguration(json);
        rule.setValidFrom(now);
        rule.setActive(true);
        rule = ruleRepository.save(rule);
        log.info("[CoverageExclusion] updated — branch={} coverage={} by={}", branchId, coverageId, actorEmail);
        return new CoverageExclusionResponse(rule.getId(), branchId, coverageId, config.excludedClaimCauseIds());
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

    private String serialize(CoverageExclusionConfig config) {
        try {
            return OBJECT_MAPPER.writeValueAsString(new CoverageExclusionConfig(config.excludedClaimCauseIds()));
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("No se pudo serializar la configuración de la regla");
        }
    }
}
