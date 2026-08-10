package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.CatalogOption;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageExclusionConfig;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageExclusionResponse;
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
 * Backoffice del referente: qué hechos generadores NO cubre una cobertura (la "exclusión dura" que el
 * motor evalúa por código y audita en {@code rule_result}, a diferencia de las exclusiones en texto
 * que solo van al prompt). Se persiste como una fila {@code COVERAGE_EXCLUSION} de {@link InsurerRule}
 * con {@code configuration} JSONB = {@link CoverageExclusionConfig} (lista de {@code claim_cause} ids).
 * Cada cambio deja snapshot en {@code insurer_rule_history} (auditoría append-only), igual que Fast
 * Track. El schema del tenant se resuelve del JWT, así que el referente solo ve/edita su aseguradora.
 */
@Service
@RequiredArgsConstructor
public class CoverageExclusionRuleService {

    private static final Logger log = LoggerFactory.getLogger(CoverageExclusionRuleService.class);
    private static final String COVERAGE_EXCLUSION = "COVERAGE_EXCLUSION";
    // Self-instanciado (Jackson 2), igual que FastTrackRuleService: Spring Boot 4 autoconfigura un
    // ObjectMapper de Jackson 3 (tools.jackson), así que no hay bean com.fasterxml para inyectar.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;
    private final BranchRepository branchRepository;
    private final ClaimCauseRepository claimCauseRepository;

    /** Hechos generadores del ramo, para poblar el selector de exclusiones (id + nombre). */
    @Transactional(readOnly = true)
    public List<CatalogOption> listClaimCauses(Long branchId) {
        return claimCauseRepository.findByBranch_IdOrderByNameAsc(branchId).stream()
                .map(cause -> new CatalogOption(cause.getId(), cause.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CoverageExclusionConfig get(Long coverageId) {
        return ruleRepository.findFirstByCoverageIdAndRuleType(coverageId, COVERAGE_EXCLUSION)
                .map(rule -> deserialize(rule.getConfiguration()))
                .orElseGet(() -> new CoverageExclusionConfig(List.of()));
    }

    @Transactional
    public CoverageExclusionResponse upsert(Long branchId, Long coverageId, CoverageExclusionConfig config, String actorEmail) {
        String json = serialize(config);
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdAndCoverageIdAndRuleType(branchId, coverageId, COVERAGE_EXCLUSION)
                .orElse(null);

        if (rule == null) {
            Branch branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new BranchNotFoundException(branchId));
            rule = InsurerRule.builder()
                    .active(true)
                    .validFrom(now)
                    .name("Exclusiones de cobertura " + coverageId)
                    .ruleType(COVERAGE_EXCLUSION)
                    .effect("RECHAZAR")
                    // Una exclusión dura hace irrelevante al Fast Track (el motor la evalúa primero).
                    .blocksFastTrack(true)
                    .branch(branch)
                    .coverageId(coverageId)
                    .configuration(json)
                    .build();
            rule = ruleRepository.save(rule);
            log.info("[CoverageExclusion] created — branch={} coverage={} by={}", branchId, coverageId, actorEmail);
            return new CoverageExclusionResponse(rule.getId(), branchId, coverageId, config.excludedClaimCauseIds());
        }

        // Snapshot de la versión que se va a pisar, antes de tocarla (auditoría append-only).
        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(rule.getConfiguration() == null ? "{}" : rule.getConfiguration())
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
