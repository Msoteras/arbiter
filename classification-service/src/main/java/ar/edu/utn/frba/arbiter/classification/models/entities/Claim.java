package ar.edu.utn.frba.arbiter.classification.models.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Persisted claim report — the facts of what was denounced. This module only
 * classifies and persists the result (see ClassificationLog); the Claim's
 * lifecycle/state belongs to cases-service.
 *
 * Documents are not stored here either — OCR is deferred and runs inline during
 * classification (see ClassificationOrchestrator), so there's nothing to keep
 * beyond these structured fields.
 */
@Entity
@Table(name = "claim")
@Getter
@Setter
@NoArgsConstructor
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String branch;

    @Column(nullable = false)
    private String product;

    @Column(name = "claim_cause", nullable = false)
    private String claimCause;

    @Column(name = "insured_item", nullable = false)
    private String insuredItem;

    @Column(name = "insured_id", nullable = false)
    private String insuredId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "event_date", nullable = false)
    private LocalDateTime eventDate;

    @Column(name = "event_location", nullable = false)
    private String eventLocation;

    @Column(name = "claimed_amount", precision = 14, scale = 2)
    private BigDecimal claimedAmount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
