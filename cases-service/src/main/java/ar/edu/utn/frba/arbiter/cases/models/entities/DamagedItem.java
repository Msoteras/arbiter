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

/**
 * The specific insured item a claim is about ("BienAsegurado"/"bien_siniestrado" in
 * CLAUDE.md's domain vocabulary) — e.g. a particular Samsung A56, IMEI included. Today
 * {@code Case.insuredItem} is a free-text field ("Samsung Galaxy A56"); this is the
 * normalized target shape, unused for now. {@code caseId}/{@code branchId} are logical
 * references (cases.id is this same module but no FK exists between entities today;
 * branch is rules-service, cross-module) — no real FK for either.
 */
@Entity
@Table(name = "damaged_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DamagedItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String brand;

    private String model;

    private String imei;

    @Column(name = "declared_value")
    private BigDecimal declaredValue;

    @Column(name = "other_attributes", columnDefinition = "TEXT")
    private String otherAttributes;

    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "branch_id")
    private Long branchId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id")
    private Policy policy;
}
