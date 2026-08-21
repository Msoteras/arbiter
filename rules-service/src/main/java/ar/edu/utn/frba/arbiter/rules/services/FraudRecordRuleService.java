package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.rules.dto.FraudRecordRuleConfig;
import ar.edu.utn.frba.arbiter.rules.dto.FraudRecordRuleDto;
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

/**
 * Referente-facing backoffice for the insurer's fraud-record policy, plus the system-to-system read
 * the classification engine uses.
 *
 * <p>Kept out of {@link InsurerHardRuleService} even though the row lives the same way (insurer-wide,
 * {@code branch_id} and {@code coverage_id} null): that service returns its two rules as a pair with
 * a shared shape, and this one is configured by a window rather than an on-arrears mode. Folding it
 * in would mean a DTO whose fields only apply to one of three rules.
 *
 * <p>Opt-in like every other hard rule: with no row, nothing vetoes Fast Track. Whether the record
 * scores is not decided here but in the scoring config, alongside every other factor. And the
 * analyst sees the record either way — what a person can see about the case in front of them was
 * never the engine's to switch off.
 */
@Service
@RequiredArgsConstructor
public class FraudRecordRuleService {

    private static final Logger log = LoggerFactory.getLogger(FraudRecordRuleService.class);

    // Self-instantiated (Jackson 2), same as the other rule services: Spring Boot 4 auto-configures
    // a Jackson 3 (tools.jackson) ObjectMapper, so there's no com.fasterxml bean to inject.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String RULE_NAME = "Antecedente de fraude del asegurado";

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;

    @Transactional(readOnly = true)
    public FraudRecordRuleDto get() {
        return ruleRepository
                .findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(RuleType.FRAUD_RECORD.name())
                .filter(InsurerRule::isActive)
                .map(rule -> new FraudRecordRuleDto(
                        rule.getId(),
                        windowMonthsOf(rule),
                        rule.isBlocksFastTrack()))
                .orElseGet(FraudRecordRuleDto::unconfigured);
    }

    @Transactional
    public FraudRecordRuleDto upsert(FraudRecordRuleDto requested, String actorEmail) {
        int windowMonths = requested.windowMonths() == null
                ? FraudRecordRuleDto.DEFAULT_WINDOW_MONTHS
                : requested.windowMonths();
        String json = serialize(new FraudRecordRuleConfig(windowMonths));
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(RuleType.FRAUD_RECORD.name())
                .orElse(null);

        if (rule == null) {
            InsurerRule created = ruleRepository.save(InsurerRule.builder()
                    // Siempre activa: la fila existe para llevar la ventana y el veto, y el "no
                    // cuenta" se expresa donde corresponde — el veto en su propio flag, el puntaje
                    // sacando el factor del scoring. Un tercer estado acá solo agregaba ambigüedad.
                    .active(true)
                    .validFrom(now)
                    .name(RULE_NAME)
                    .ruleType(RuleType.FRAUD_RECORD.name())
                    // DERIVAR and never RECHAZAR: a record about the person is not "una causa legal
                    // o convencional de exclusión" for this claim (human-in-the-loop, decisión #5).
                    // It hands the analyst the claim with the finding attached.
                    .effect("DERIVAR")
                    .blocksFastTrack(requested.blocksFastTrack())
                    .branch(null)
                    .coverageId(null)
                    .configuration(json)
                    .build());
            log.info("[FraudRecordRule] created — windowMonths={} blocksFastTrack={} by={}",
                    windowMonths, requested.blocksFastTrack(), actorEmail);
            return new FraudRecordRuleDto(created.getId(), windowMonths, created.isBlocksFastTrack());
        }

        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(rule.getConfiguration() == null ? "{}" : rule.getConfiguration())
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Fraud record rule updated by " + actorEmail)
                .insurerRule(rule)
                .changedBy(null)
                .build());

        rule.setActive(true);
        rule.setBlocksFastTrack(requested.blocksFastTrack());
        rule.setConfiguration(json);
        rule.setValidFrom(now);
        ruleRepository.save(rule);
        log.info("[FraudRecordRule] updated — windowMonths={} blocksFastTrack={} by={}",
                windowMonths, requested.blocksFastTrack(), actorEmail);

        return new FraudRecordRuleDto(rule.getId(), windowMonths, rule.isBlocksFastTrack());
    }

    /**
     * An unreadable configuration falls back to the default window instead of sinking the read: the
     * rule being active is the decision that matters, and a claim shouldn't fail to classify
     * because one JSON field got mangled.
     */
    private int windowMonthsOf(InsurerRule rule) {
        String json = rule.getConfiguration();
        if (json == null || json.isBlank()) {
            return FraudRecordRuleDto.DEFAULT_WINDOW_MONTHS;
        }
        try {
            Integer months = OBJECT_MAPPER.readValue(json, FraudRecordRuleConfig.class).windowMonths();
            return months == null ? FraudRecordRuleDto.DEFAULT_WINDOW_MONTHS : months;
        } catch (JsonProcessingException e) {
            log.warn("[FraudRecordRule] Unreadable configuration on rule {} — falling back to {} months",
                    rule.getId(), FraudRecordRuleDto.DEFAULT_WINDOW_MONTHS);
            return FraudRecordRuleDto.DEFAULT_WINDOW_MONTHS;
        }
    }

    private String serialize(FraudRecordRuleConfig config) {
        try {
            return OBJECT_MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("Could not serialize the fraud record configuration");
        }
    }
}
