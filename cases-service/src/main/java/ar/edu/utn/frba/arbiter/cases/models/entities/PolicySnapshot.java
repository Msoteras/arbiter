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
 * What the insurer's DB answered for one specific claim, frozen at the moment it was filed
 * ("poliza_consultada" in the DER). Distinct from {@link Policy}, which is the synced local
 * copy that keeps changing: this one must not, or the classification stops being reproducible
 * — the audit trail Disposición SSN 2/2023 requires has to show the data the decision was
 * actually made on.
 *
 * <p>{@code paymentsUpToDate} and {@code previousClaims} are what the scoring engine reads for
 * the {@code policy_standing} and {@code claim_frequency} factors.
 *
 * <p>This is the <b>read</b> side. classification-service writes it (its
 * {@code PolicySnapshotRepository}, plain JDBC), because that's where the insurer's answer enters
 * the decision — taking the snapshot here, on creation, would record data the classification never
 * saw.
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

    /** Nullable, unlike the count: a zero would read as "never claimed a peso". */
    @Column(name = "total_amount_claimed")
    private BigDecimal totalAmountClaimed;

    @Column(name = "queried_at", nullable = false)
    private Instant queriedAt;

    /** Raw insurer response, kept whole for audit. jsonb in the schema, not TEXT. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "insurer_db_payload")
    private String insurerDbPayload;
}
