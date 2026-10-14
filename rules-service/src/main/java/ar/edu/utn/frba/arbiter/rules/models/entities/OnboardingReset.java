package ar.edu.utn.frba.arbiter.rules.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** The last restart of the checklist. A single row: after it, a step only counts once saved again. */
@Entity
@Table(name = "onboarding_reset")
@Getter
@Setter
@NoArgsConstructor
public class OnboardingReset {

    public static final short SINGLE_ROW_ID = 1;

    @Id
    private Short id = SINGLE_ROW_ID;

    @Column(name = "reset_at", nullable = false)
    private Instant resetAt;

    @Column(name = "reset_by")
    private Long resetBy;
}
