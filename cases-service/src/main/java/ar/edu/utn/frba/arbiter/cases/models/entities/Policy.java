package ar.edu.utn.frba.arbiter.cases.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Local persisted copy of a policy ("Poliza" in CLAUDE.md's domain vocabulary) — the
 * snapshot-sync target described in CLAUDE.md decision #10 ("Arbiter persiste snapshots
 * locales... un cron trae los datos"). Today {@link ar.edu.utn.frba.arbiter.cases.controllers.PolicyController}
 * reads policies live from {@code InsurerAdapter}, never from a local table; this doesn't
 * replace that yet. {@code insuredId} is a logical reference to auth-service's Insured —
 * cross-module, no real FK.
 */
@Entity
@Table(name = "policy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "insured_id", nullable = false)
    private Long insuredId;

    @Column(name = "external_policy_number", nullable = false)
    private String externalPolicyNumber;

    private String product;

    @Column(name = "sum_insured", nullable = false)
    private BigDecimal sumInsured;

    @Column(name = "in_force", nullable = false)
    private boolean inForce;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coverage_id")
    private Coverage coverage;
}
