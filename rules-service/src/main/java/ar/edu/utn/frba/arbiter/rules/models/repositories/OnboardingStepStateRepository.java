package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.OnboardingStepState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OnboardingStepStateRepository extends JpaRepository<OnboardingStepState, String> {

    @Modifying
    @Query("UPDATE OnboardingStepState s SET s.skipped = false WHERE s.skipped = true")
    int clearSkipped();
}
