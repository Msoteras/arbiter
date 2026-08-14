package ar.edu.utn.frba.arbiter.common.models.entities;

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

/**
 * Cause of the claim (robo en vía pública, hurto, caída, incendio…) — "HechoGenerador"
 * in CLAUDE.md's domain vocabulary. The central field the LLM classifies.
 */
@Entity
@Table(name = "claim_cause")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClaimCause {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // EAGER on purpose: the branch travels in every case response
    // (CaseServiceImpl.toResponse reads claimCause.getBranch().getName()), and with open-in-view off
    // there's no open session when the DTO is built. Wrapping the service in @Transactional isn't
    // enough: the multi-insurer sweep switches schema mid-method, and a transaction holds on to a
    // connection, so the search_path would stay pinned to the first one.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;
}
