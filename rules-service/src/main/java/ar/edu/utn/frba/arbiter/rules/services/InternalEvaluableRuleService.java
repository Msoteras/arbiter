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
 * Lectura system-to-system de las reglas <b>duras evaluables</b> de una cobertura para el motor de
 * clasificación (sin rol REFERENTE, con token de servicio que lleva el tenant) — mismo criterio que
 * {@code /internal/fast-track} y {@code /internal/rule-texts}. Keyea por cobertura, que es lo que el
 * claim tiene a mano.
 *
 * <p>Hoy el único tipo es {@code COVERAGE_EXCLUSION}: la lista negra de hechos generadores que la
 * cobertura no cubre, persistida como una fila de {@code insurer_rule} con {@code configuration}
 * JSONB. Esto cierra la asimetría del DER, donde no existe relación cobertura ↔ hecho generador
 * (ver plan-reglas-evaluables.md §1.2). El {@code id} de la regla viaja al motor porque es lo que
 * después va a {@code rule_result.rule_id} (auditoría, Disposición SSN 2/2023).
 */
@Service
public class InternalEvaluableRuleService {

    private static final String COVERAGE_EXCLUSION = "COVERAGE_EXCLUSION";
    // Self-instanciado (Jackson 2), igual que FastTrackRuleService: Spring Boot 4 autoconfigura un
    // ObjectMapper de Jackson 3 (tools.jackson), así que no hay bean com.fasterxml para inyectar.
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
