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
 * How much an analyst can authorize on their own in a branch; above it the referente signs. Per
 * branch, not per analyst, since there is a single analyst role. No row means no limit.
 */
@Entity
@Table(name = "settlement_authority")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementAuthority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Logical reference to the common-schema branch (no FK across schemas). */
    @Column(name = "branch_id", nullable = false, unique = true)
    private Long branchId;

    /** Inclusive: a settlement exactly at this amount needs no referente. */
    @Column(name = "max_amount", nullable = false)
    private BigDecimal maxAmount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Logical reference to arbiter_common.users. */
    @Column(name = "updated_by")
    private Long updatedBy;
}
