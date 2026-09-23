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
 * Case-status catalog that {@code cases.current_status_id} and {@code case_status_history} point
 * to. {@code name} is 1:1 with {@code CaseStatus}: the enum literal is what resolves a row, so a
 * row the enum doesn't know about isn't supported.
 */
@Entity
@Table(name = "case_status", schema = "arbiter_common")
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
