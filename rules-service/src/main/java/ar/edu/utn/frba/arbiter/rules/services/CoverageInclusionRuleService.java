package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.CatalogOption;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageInclusionConfig;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageInclusionResponse;
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
 * Backoffice del referente: qué hechos generadores SÍ cubre una cobertura (la "inclusión dura" que el
 * motor evalúa por código y audita en {@code rule_result}, a diferencia de las exclusiones en texto
 * que solo van al prompt). Se persiste como una fila {@code COVERAGE_INCLUSION} de {@link InsurerRule}
 * con {@code configuration} JSONB = {@link CoverageInclusionConfig} (lista de {@code claim_cause}
 * ids). Cada cambio deja snapshot en {@code insurer_rule_history} (auditoría append-only), igual que
 * Fast Track. El schema del tenant se resuelve del JWT, así que el referente solo ve/edita su
 * aseguradora.
 *
 * <p>Antes de esta lista fue una lista NEGRA (COVERAGE_EXCLUSION): sin regla, una cobertura cubría
 * todo por default. Se descartó porque el default permisivo dejaba pasar denuncias de hechos
 * generadores que la cobertura nunca tuvo que cubrir (ej. una caída sobre una cobertura de robo) sin
 * que nadie configurara nada — un fail-open peligroso para un sistema de seguros. Ahora, sin regla,
 * una cobertura no cubre nada: hay que cargar explícitamente qué cubre cada una.
 */
@Service
@RequiredArgsConstructor
public class CoverageInclusionRuleService {

    private static final Logger log = LoggerFactory.getLogger(CoverageInclusionRuleService.class);
    private static final String COVERAGE_INCLUSION = "COVERAGE_INCLUSION";
    // Self-instanciado (Jackson 2), igual que FastTrackRuleService: Spring Boot 4 autoconfigura un
    // ObjectMapper de Jackson 3 (tools.jackson), así que no hay bean com.fasterxml para inyectar.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;
    private final BranchRepository branchRepository;
    private final ClaimCauseRepository claimCauseRepository;

    /** Hechos generadores del ramo, para poblar el selector de inclusiones (id + nombre). */
    @Transactional(readOnly = true)
    public List<CatalogOption> listClaimCauses(Long branchId) {
        return claimCauseRepository.findByBranch_IdOrderByNameAsc(branchId).stream()
                .map(cause -> new CatalogOption(cause.getId(), cause.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CoverageInclusionConfig get(Long coverageId) {
        return ruleRepository.findFirstByCoverageIdAndRuleType(coverageId, COVERAGE_INCLUSION)
                .map(rule -> deserialize(rule.getConfiguration()))
                .orElseGet(() -> new CoverageInclusionConfig(List.of()));
    }

    @Transactional
    public CoverageInclusionResponse upsert(Long branchId, Long coverageId, CoverageInclusionConfig config, String actorEmail) {
        String json = serialize(config);
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdAndCoverageIdAndRuleType(branchId, coverageId, COVERAGE_INCLUSION)
                .orElse(null);

        if (rule == null) {
            Branch branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new BranchNotFoundException(branchId));
            rule = InsurerRule.builder()
                    .active(true)
                    .validFrom(now)
                    .name("Hechos generadores cubiertos " + coverageId)
                    .ruleType(COVERAGE_INCLUSION)
                    .effect("RECHAZAR")
                    // Un hecho generador no incluido hace irrelevante al Fast Track (el motor la evalúa primero).
                    .blocksFastTrack(true)
                    .branch(branch)
                    .coverageId(coverageId)
                    .configuration(json)
                    .build();
            rule = ruleRepository.save(rule);
            log.info("[CoverageInclusion] created — branch={} coverage={} by={}", branchId, coverageId, actorEmail);
            return new CoverageInclusionResponse(rule.getId(), branchId, coverageId, config.includedClaimCauseIds());
        }

        // Snapshot de la versión que se va a pisar, antes de tocarla (auditoría append-only).
        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(rule.getConfiguration() == null ? "{}" : rule.getConfiguration())
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Hechos generadores cubiertos actualizados por " + actorEmail)
                .insurerRule(rule)
                .changedBy(null)
                .build());

        rule.setConfiguration(json);
        rule.setValidFrom(now);
        rule.setActive(true);
        rule = ruleRepository.save(rule);
        log.info("[CoverageInclusion] updated — branch={} coverage={} by={}", branchId, coverageId, actorEmail);
        return new CoverageInclusionResponse(rule.getId(), branchId, coverageId, config.includedClaimCauseIds());
    }

    private CoverageInclusionConfig deserialize(String json) {
        if (json == null || json.isBlank()) {
            return new CoverageInclusionConfig(List.of());
        }
        try {
            return OBJECT_MAPPER.readValue(json, CoverageInclusionConfig.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("Configuración de inclusión ilegible: " + e.getOriginalMessage());
        }
    }

    private String serialize(CoverageInclusionConfig config) {
        try {
            return OBJECT_MAPPER.writeValueAsString(new CoverageInclusionConfig(config.includedClaimCauseIds()));
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("No se pudo serializar la configuración de la regla");
        }
    }
}
