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
 * Local snapshot of a policy synced from the insurer DB. Coverages, with their own sum insured and
 * deductible, live in {@link PolicyCoverage}: a policy has several.
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
