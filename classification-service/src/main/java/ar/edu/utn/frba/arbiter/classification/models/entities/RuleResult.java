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

/** Audit of one hard-rule evaluation, PASS or FAIL (SSN Disposition 2/2023). */
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

    /** Null for coverage-scope rules: they live on the coverage, not in {@code insurer_rule}. */
    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;
}
