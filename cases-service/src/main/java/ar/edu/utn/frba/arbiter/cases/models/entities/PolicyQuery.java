package ar.edu.utn.frba.arbiter.cases.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One-off snapshot of a live query against the insurer's DB ("poliza_consultada" in the
 * DER) — {@code insurerPayload} keeps the raw response for audit. Distinct from
 * {@link Policy}: that's the synced local copy, this is a point-in-time query log. No
 * caller writes here yet — {@code InsurerAdapter} calls aren't logged today.
 */
@Entity
@Table(name = "policy_query")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_policy_number", nullable = false)
    private String externalPolicyNumber;

    @Column(name = "insured_amount")
    private BigDecimal insuredAmount;

    @Column(nullable = false)
    private boolean current;

    @Column(name = "up_to_date", nullable = false)
    private boolean upToDate;

    @Column(name = "queried_at", nullable = false)
    private Instant queriedAt;

    @Column(name = "insurer_payload", columnDefinition = "TEXT")
    private String insurerPayload;

    @Column(name = "prior_claims")
    private Integer priorClaims;
}
