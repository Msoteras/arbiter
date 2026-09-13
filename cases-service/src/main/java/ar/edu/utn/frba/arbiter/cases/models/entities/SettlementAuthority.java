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
 * How much an analyst can authorize on their own in a branch ("atribución" in the insurer's
 * vocabulary). Straight out of Anexo II of their procedure: an analyst approves settlements
 * "hasta el límite del atributo asignado por rama", and past it someone above them signs.
 *
 * <p><b>Per branch, not per analyst.</b> Anexo II crosses branch × seniority, and Arbiter has a
 * single analyst role, so the seniority axis has nothing to vary over. If senior and junior
 * analysts ever exist, this grows a column; inventing the distinction now would be modelling an
 * org chart nobody has.
 *
 * <p><b>No row means no limit.</b> The analyst authorizes everything, which is how it worked
 * before this existed. Setting a limit is the referente's explicit act — turning it on by default
 * would change the circuit of claims nobody looked at.
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

    /** Logical reference to the common-schema branch — cross-module, unique per insurer. */
    @Column(name = "branch_id", nullable = false, unique = true)
    private Long branchId;

    /** The ceiling, inclusive: a settlement exactly at this amount still needs no referente. */
    @Column(name = "max_amount", nullable = false)
    private BigDecimal maxAmount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
