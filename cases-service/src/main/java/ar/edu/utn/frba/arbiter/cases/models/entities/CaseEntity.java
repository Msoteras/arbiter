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
import java.time.LocalDateTime;

@Entity
@Table(name = "cases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String branch;

    @Column(nullable = false)
    private String product;

    @Column(nullable = false)
    private String claimCause;

    @Column(nullable = false)
    private String insuredItem;

    @Column(nullable = false)
    private String insuredId;

    @Column(nullable = false)
    private String policyNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private LocalDateTime eventDate;

    @Column(nullable = false)
    private String eventLocation;

    private BigDecimal claimedAmount;

    @Column(nullable = false)
    private String status;

    private Long classificationClaimId;

    private String analysisClassification;

    private Double analysisConfidence;

    @Column(columnDefinition = "TEXT")
    private String analysisDetail;

    private Boolean deterministicFastTrack;
}
