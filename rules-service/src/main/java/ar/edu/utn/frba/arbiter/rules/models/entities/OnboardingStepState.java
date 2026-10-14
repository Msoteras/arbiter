package ar.edu.utn.frba.arbiter.rules.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * What the configuration can't tell about a checklist step: whether the insurer marked it as not
 * used, and when its section was last saved. No row means neither.
 */
@Entity
@Table(name = "onboarding_step")
@Getter
@Setter
@NoArgsConstructor
public class OnboardingStepState {

    /** The step name, plus {@code :<branch_id>} for a per-branch step. */
    @Id
    @Column(name = "step_key", length = 40)
    private String stepKey;

    @Column(nullable = false)
    private boolean skipped;

    @Column(name = "touched_at")
    private Instant touchedAt;

    public OnboardingStepState(String stepKey) {
        this.stepKey = stepKey;
    }
}
