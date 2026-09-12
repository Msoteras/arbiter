package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerRuleSnapshot;
import ar.edu.utn.frba.arbiter.rules.dto.ResolutionTargetDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
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
import java.util.Map;

/**
 * El objetivo de resolución de la aseguradora, que el referente fija desde el panel de reglas.
 *
 * <p>Se guarda como una fila de {@code insurer_rule} de toda la compañía (sin rama ni cobertura),
 * igual que la mora y la vigencia, pero es de la familia de <b>configuración</b>: nadie la evalúa
 * contra un siniestro, no bloquea Fast Track y no deja {@code rule_result}. Vive acá y no en
 * reports-service porque es configuración de la aseguradora, y toda la configuración de la
 * aseguradora la administra este módulo desde una sola pantalla.
 *
 * <p>Cada cambio deja snapshot en {@code insurer_rule_history}, igual que el resto: subir el
 * objetivo de 21 a 35 días hace que la mitad de los expedientes deje de estar "fuera de objetivo"
 * de un día para el otro, y eso tiene que quedar registrado.
 */
@Service
@RequiredArgsConstructor
public class ResolutionTargetService {

    private static final Logger log = LoggerFactory.getLogger(ResolutionTargetService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RULE_NAME = "Objetivo de resolución";
    private static final String TARGET_DAYS = "targetDays";

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;

    @Transactional(readOnly = true)
    public ResolutionTargetDto get() {
        return ruleRepository
                .findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(RuleType.RESOLUTION_TARGET.name())
                .map(rule -> {
                    Integer days = targetDaysOf(rule.getConfiguration());
                    // Una fila activa sin días es una fila a medio configurar: vale lo mismo que
                    // no tener objetivo, y el tablero no tiene contra qué comparar.
                    return days == null
                            ? ResolutionTargetDto.unset()
                            : new ResolutionTargetDto(rule.isActive(), days);
                })
                .orElseGet(ResolutionTargetDto::unset);
    }

    @Transactional
    public ResolutionTargetDto upsert(ResolutionTargetDto requested, String actorEmail) {
        if (requested.enabled() && requested.targetDays() == null) {
            throw new InvalidRuleConfigurationException(
                    "A resolution target that is on needs targetDays");
        }
        // Apagar el objetivo no borra el número: si lo vuelven a encender, vuelve el que había.
        Integer days = requested.targetDays();
        String json = serialize(days);
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(RuleType.RESOLUTION_TARGET.name())
                .orElse(null);

        if (rule == null) {
            ruleRepository.save(InsurerRule.builder()
                    .active(requested.enabled())
                    .validFrom(now)
                    .name(RULE_NAME)
                    .ruleType(RuleType.RESOLUTION_TARGET.name())
                    // Sin efecto y sin prioridad: no se evalúa, así que no hay nada que efectuar.
                    // blocksFastTrack=false por el mismo motivo — pasarse del objetivo de gestión
                    // no puede sacarle a un siniestro la vía rápida.
                    .effect(null)
                    .blocksFastTrack(false)
                    .branch(null)
                    .coverageId(null)
                    .configuration(json)
                    .build());
            log.info("[ResolutionTarget] created — enabled={} targetDays={} by={}",
                    requested.enabled(), days, actorEmail);
            return get();
        }

        if (InsurerRuleSnapshot.unchanged(
                rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration(),
                requested.enabled(), rule.isBlocksFastTrack(), json)) {
            return get();
        }

        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(InsurerRuleSnapshot.serialize(
                        rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration()))
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Objetivo de resolución actualizado por " + actorEmail)
                .insurerRule(rule)
                .changedBy(null)
                .build());

        rule.setActive(requested.enabled());
        rule.setConfiguration(json);
        rule.setValidFrom(now);
        ruleRepository.save(rule);
        log.info("[ResolutionTarget] updated — enabled={} targetDays={} by={}",
                requested.enabled(), days, actorEmail);
        return get();
    }

    private Integer targetDaysOf(String configuration) {
        if (configuration == null || configuration.isBlank()) {
            return null;
        }
        try {
            Object value = OBJECT_MAPPER.readValue(configuration, Map.class).get(TARGET_DAYS);
            return value instanceof Number number ? number.intValue() : null;
        } catch (JsonProcessingException malformed) {
            // Una configuración ilegible no puede tumbar el panel entero: se lee como "sin objetivo".
            log.warn("[ResolutionTarget] unreadable configuration, treated as unset: {}", configuration);
            return null;
        }
    }

    private String serialize(Integer targetDays) {
        try {
            return OBJECT_MAPPER.writeValueAsString(
                    targetDays == null ? Map.of() : Map.of(TARGET_DAYS, targetDays));
        } catch (JsonProcessingException impossible) {
            throw new InvalidRuleConfigurationException("Could not serialize the resolution target");
        }
    }
}
