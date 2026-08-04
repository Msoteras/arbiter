package ar.edu.utn.frba.arbiter.common.models.entities;

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

/**
 * Configurable case-status catalog ("estado_expediente" in the DER) — the target shape
 * for H0015 ("el referente puede definir los estados activos del flujo").
 *
 * <p>{@code cases.current_status_id} and {@code case_status_history} point here. {@code name}
 * is still 1:1 with common-lib's {@code CaseStatus} enum, which stays as the vocabulary the
 * state machine and the API speak; the enum literal is what resolves a row (the column is
 * UNIQUE). Adding a row the enum doesn't know about is the next step of H0015, not this one.
 */
@Entity
@Table(name = "case_status")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    /** Aggregated label the insured sees (several internal states can map to the same one). */
    @Column(name = "insured_status", nullable = false)
    private String insuredState;

    @Column(name = "is_final", nullable = false)
    private boolean isFinal;
}
