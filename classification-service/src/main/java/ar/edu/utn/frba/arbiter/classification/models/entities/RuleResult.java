package ar.edu.utn.frba.arbiter.classification.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Audit record of a single business-rule evaluation against a claim
 * ("resultado_regla" in the DER) — no rule engine evaluates anything into this table yet.
 * ruleId is a logical reference to rules-service's InsurerRule (there's no rule common to
 * every insurer to point at instead) and caseId to cases-service's Case — neither is a
 * real FK, same criterion as LlmAnalysis.caseId.
 */
@Entity
@Table(name = "rule_result")
@Getter
@Setter
@NoArgsConstructor
public class RuleResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_type", nullable = false)
    private String ruleType;

    @Column(nullable = false)
    private String result;

    @Column(name = "evaluated_value")
    private String evaluatedValue;

    @Column(name = "score_contribution", precision = 5, scale = 4)
    private BigDecimal scoreContribution;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;
}
