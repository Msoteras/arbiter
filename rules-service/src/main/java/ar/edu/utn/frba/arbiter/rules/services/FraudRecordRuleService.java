package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.rules.dto.FraudRecordRuleConfig;
import ar.edu.utn.frba.arbiter.rules.dto.FraudRecordRuleDto;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerRuleSnapshot;
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
 * The insurer's fraud-record policy, for the referente and for the classification engine. Kept out
 * of {@link InsurerHardRuleService} because it's configured by a window, not an on-arrears mode.
 *
 * <p>Opt-in: with no row, nothing vetoes Fast Track. Whether the record scores is decided in the
 * scoring config, and the analyst sees the record either way.
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
    private final RuleAuthorResolver authorResolver;

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
                    // Always active: "doesn't count" is expressed by the veto flag and by removing
                    // the factor from the scoring config.
                    .active(true)
                    .validFrom(now)
                    .createdBy(authorResolver.userIdOf(actorEmail))
                    .name(RULE_NAME)
                    .ruleType(RuleType.FRAUD_RECORD.name())
                    // DERIVAR, never RECHAZAR: a record about the person is not a legal exclusion
                    // cause for this claim, so the analyst gets it with the finding attached.
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

        // A save that changes nothing leaves no audit entry.
        if (InsurerRuleSnapshot.unchanged(
                rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration(),
                true, requested.blocksFastTrack(), json)) {
            return new FraudRecordRuleDto(rule.getId(), windowMonths, rule.isBlocksFastTrack());
        }

        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(InsurerRuleSnapshot.serialize(
                        rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration()))
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Antecedente de fraude actualizado por " + actorEmail)
                .insurerRule(rule)
                .changedBy(authorResolver.referentIdOf(actorEmail))
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

    /** An unreadable configuration falls back to the default window so classification never fails on it. */
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
