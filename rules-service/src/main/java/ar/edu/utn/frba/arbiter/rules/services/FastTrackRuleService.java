package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.CatalogOption;
import ar.edu.utn.frba.arbiter.rules.dto.FastTrackConfigDto;
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
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Reads and writes a referente's Fast Track thresholds for one (rama, cobertura). The rule is
 * persisted as a single {@code FAST_TRACK} {@link InsurerRule} row whose {@code configuration}
 * (JSONB) carries the {@link FastTrackConfigDto} — DER-consistent (a rule scopes by branch +
 * coverage) and 1:1 with classification's {@code BusinessRules.FastTrackThresholds}. Every write
 * snapshots the previous version into {@code insurer_rule_history} (append-only, DER's
 * "historial_regla_aseguradora"). The tenant schema is resolved from the JWT upstream, so all
 * queries here already target the caller's insurer.
 */
@Service
@RequiredArgsConstructor
public class FastTrackRuleService {

    private static final Logger log = LoggerFactory.getLogger(FastTrackRuleService.class);
    private static final String FAST_TRACK = "FAST_TRACK";
    // Self-instantiated (Jackson 2), matching the *JsonConverter classes: Spring Boot 4 auto-configures
    // a Jackson 3 (tools.jackson) ObjectMapper, so there's no com.fasterxml bean to inject here.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public FastTrackConfigDto get(Long branchId, Long coverageId) {
        return ruleRepository.findFirstByBranch_IdAndCoverageIdAndRuleType(branchId, coverageId, FAST_TRACK)
                .map(rule -> deserialize(rule.getConfiguration()))
                .orElseGet(FastTrackConfigDto::empty);
    }

    /** By coverage alone, for the system-to-system read from classification-service. */
    @Transactional(readOnly = true)
    public FastTrackConfigDto getByCoverage(Long coverageId) {
        return ruleRepository.findFirstByCoverageIdAndRuleType(coverageId, FAST_TRACK)
                .map(rule -> deserialize(rule.getConfiguration()))
                .orElseGet(FastTrackConfigDto::empty);
    }

    @Transactional(readOnly = true)
    public List<CatalogOption> listBranches() {
        return branchRepository.findAll(Sort.by("name")).stream()
                .map(branch -> new CatalogOption(branch.getId(), branch.getName()))
                .toList();
    }

    @Transactional
    public void upsert(Long branchId, Long coverageId, FastTrackConfigDto config, String actorEmail) {
        String json = serialize(config);
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdAndCoverageIdAndRuleType(branchId, coverageId, FAST_TRACK)
                .orElse(null);

        if (rule == null) {
            Branch branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new BranchNotFoundException(branchId));
            rule = InsurerRule.builder()
                    .active(true)
                    .validFrom(now)
                    .name("Fast Track — cobertura " + coverageId)
                    .ruleType(FAST_TRACK)
                    .blocksFastTrack(false)
                    .branch(branch)
                    .coverageId(coverageId)
                    .configuration(json)
                    .build();
            ruleRepository.save(rule);
            log.info("[FastTrackRule] created — branch={} coverage={} by={}", branchId, coverageId, actorEmail);
            return;
        }

        // Snapshot the version we're about to overwrite before touching it (append-only audit).
        // changedBy (the referente's numeric id) is left null for now: the JWT carries the actor's
        // email, not the insurer_referent id — the actor is recorded in `reason` until a userId
        // claim (or an email->referent lookup) is wired.
        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(rule.getConfiguration() == null ? "{}" : rule.getConfiguration())
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Fast Track actualizado por " + actorEmail)
                .insurerRule(rule)
                .changedBy(null)
                .build());

        rule.setConfiguration(json);
        rule.setValidFrom(now);
        ruleRepository.save(rule);
        log.info("[FastTrackRule] updated — branch={} coverage={} by={}", branchId, coverageId, actorEmail);
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
