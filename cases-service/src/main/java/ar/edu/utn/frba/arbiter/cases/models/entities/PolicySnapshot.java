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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the insurer DB answered for one claim, frozen so the classification stays reproducible for
 * audit (unlike {@link Policy}, which keeps syncing). Read-only here: classification-service writes it,
 * since that's where the insurer's answer enters the decision.
 */
@Entity
@Table(name = "policy_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_policy_number", nullable = false)
    private String externalPolicyNumber;

    @Column(name = "sum_insured", nullable = false)
    private BigDecimal sumInsured;

    @Column(name = "in_force", nullable = false)
    private boolean inForce;

    @Column(name = "payments_up_to_date", nullable = false)
    private boolean paymentsUpToDate;

    @Column(name = "previous_claims", nullable = false)
    private Integer previousClaims;

    /** Nullable, unlike the count: a zero would read as "never paid anything". */
    @Column(name = "total_amount_claimed")
    private BigDecimal totalAmountClaimed;

    /** The settlement counts the installments still due between the event and this date. */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** Null where the insurer DB doesn't carry it. */
    @Column(name = "installment_amount")
    private BigDecimal installmentAmount;

    /** Same fact as {@link #paymentsUpToDate}, in money: rules ask whether there's debt, the settlement how much. */
    @Column(name = "overdue_balance")
    private BigDecimal overdueBalance;

    /**
     * 1 is the first. Uses the same 12-month window as the MAX_EVENTS_YEAR rule, so the events cap and
     * the percentage payable can't disagree.
     */
    @Column(name = "events_in_year")
    private Integer eventsInYear;

    @Column(name = "queried_at", nullable = false)
    private Instant queriedAt;

    /** Raw insurer response, kept whole for audit. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "insurer_db_payload")
    private String insurerDbPayload;
}
