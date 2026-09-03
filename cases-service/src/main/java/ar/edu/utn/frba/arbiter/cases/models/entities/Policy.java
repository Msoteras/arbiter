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

import java.time.Instant;

/**
 * Local persisted copy of a policy ("Poliza" in CLAUDE.md's domain vocabulary) — the
 * snapshot-sync target described in CLAUDE.md decision #10 ("Arbiter persiste snapshots
 * locales... un cron trae los datos"). Today {@link ar.edu.utn.frba.arbiter.cases.controllers.PolicyController}
 * reads policies live from {@code InsurerAdapter}, never from a local table; this doesn't
 * replace that yet. {@code insuredId} is a logical reference to auth-service's Insured —
 * cross-module, no real FK.
 *
 * <p><b>No carries neither the coverage nor the sum insured.</b> A policy has SEVERAL coverages
 * and each one has its own sum insured and deductible — that's how the insurer DB models it
 * ({@code poliza 1──* cobertura}) and what the contract actually says. Those live in
 * {@link PolicyCoverage}, one row per contracted coverage. Holding a single {@code coverage_id}
 * here meant {@code PolicySynchronizer} imported the first coverage the company returned and
 * dropped the rest.
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

    @Column(name = "in_force", nullable = false)
    private boolean inForce;

    @Column(name = "synced_at")
    private Instant syncedAt;
}
