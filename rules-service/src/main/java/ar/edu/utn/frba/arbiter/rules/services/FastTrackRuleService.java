package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.FastTrackConfigDto;
import ar.edu.utn.frba.arbiter.rules.dto.FastTrackRuleResponse;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerRuleSnapshot;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
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

/**
 * A referente's Fast Track thresholds for one (branch, coverage), persisted as a single
 * {@code FAST_TRACK} {@link InsurerRule} whose configuration is a {@link FastTrackConfigDto}
 * (1:1 with classification-service's {@code BusinessRules.FastTrackThresholds}). Every write
 * snapshots the previous version into {@code insurer_rule_history}.
 */
@Service
@RequiredArgsConstructor
public class FastTrackRuleService {

    private static final Logger log = LoggerFactory.getLogger(FastTrackRuleService.class);
    // Self-instantiated (Jackson 2), matching the *JsonConverter classes: Spring Boot 4 auto-configures
    // a Jackson 3 (tools.jackson) ObjectMapper, so there's no com.fasterxml bean to inject here.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;
    private final BranchRepository branchRepository;
    private final CoverageRepository coverageRepository;
    private final RuleAuthorResolver authorResolver;

    @Transactional(readOnly = true)
    public FastTrackConfigDto get(Long branchId, Long coverageId) {
        return ruleRepository.findFirstByBranch_IdAndCoverageIdAndRuleType(branchId, coverageId, RuleType.FAST_TRACK.name())
                .map(rule -> deserialize(rule.getConfiguration()))
                .orElseGet(FastTrackConfigDto::empty);
    }

    /** By coverage alone, for the system-to-system read from classification-service. */
    @Transactional(readOnly = true)
    public FastTrackConfigDto getByCoverage(Long coverageId) {
        return ruleRepository.findFirstByCoverageIdAndRuleType(coverageId, RuleType.FAST_TRACK.name())
                .map(rule -> deserialize(rule.getConfiguration()))
                .orElseGet(FastTrackConfigDto::empty);
    }

    @Transactional
    public FastTrackRuleResponse upsert(Long branchId, Long coverageId, FastTrackConfigDto config, String actorEmail) {
        requireCoverageInBranch(branchId, coverageId);
        String json = serialize(config);
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdAndCoverageIdAndRuleType(branchId, coverageId, RuleType.FAST_TRACK.name())
                .orElse(null);

        if (rule == null) {
            Branch branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new BranchNotFoundException(branchId));
            rule = InsurerRule.builder()
                    .active(true)
                    .validFrom(now)
                    .createdBy(authorResolver.userIdOf(actorEmail))
                    .name("Fast Track — cobertura " + coverageId)
                    .ruleType(RuleType.FAST_TRACK.name())
                    .blocksFastTrack(false)
                    .branch(branch)
                    .coverageId(coverageId)
                    .configuration(json)
                    .build();
            rule = ruleRepository.save(rule);
            log.info("[FastTrackRule] created — branch={} coverage={} by={}", branchId, coverageId, actorEmail);
            return new FastTrackRuleResponse(rule.getId(), branchId, coverageId, config);
        }

        if (InsurerRuleSnapshot.unchanged(
                rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration(),
                rule.isActive(), rule.isBlocksFastTrack(), json)) {
            return new FastTrackRuleResponse(rule.getId(), branchId, coverageId, config);
        }

        // Snapshot the version about to be overwritten.
        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(InsurerRuleSnapshot.serialize(
                        rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration()))
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Fast Track actualizado por " + actorEmail)
                .insurerRule(rule)
                .changedBy(authorResolver.referentIdOf(actorEmail))
                .build());

        rule.setConfiguration(json);
        rule.setValidFrom(now);
        rule = ruleRepository.save(rule);
        log.info("[FastTrackRule] updated — branch={} coverage={} by={}", branchId, coverageId, actorEmail);
        return new FastTrackRuleResponse(rule.getId(), branchId, coverageId, config);
    }

    /**
     * The classification engine reads this rule by coverage alone ({@link #getByCoverage}), so a row
     * saved under a (branch, coverage) pair that doesn't match the catalog would still be picked up —
     * with the wrong branch recorded next to it. Reject the pair before anything is written.
     */
    private void requireCoverageInBranch(Long branchId, Long coverageId) {
        Long coverageBranchId = coverageRepository.findById(coverageId)
                .map(coverage -> coverage.getBranchId())
                .orElseThrow(() -> new InvalidRuleConfigurationException(
                        "No existe la cobertura con id " + coverageId));
        if (!coverageBranchId.equals(branchId)) {
            throw new InvalidRuleConfigurationException(
                    "La cobertura " + coverageId + " no pertenece al ramo " + branchId);
        }
    }

    private FastTrackConfigDto deserialize(String json) {
        if (json == null || json.isBlank()) {
            return FastTrackConfigDto.empty();
        }
        try {
            return OBJECT_MAPPER.readValue(json, FastTrackConfigDto.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("Configuración de regla ilegible: " + e.getOriginalMessage());
        }
    }

    private String serialize(FastTrackConfigDto config) {
        try {
            return OBJECT_MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("No se pudo serializar la configuración de la regla");
        }
    }
}
