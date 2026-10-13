package ar.edu.utn.frba.arbiter.common.dto;

import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import jakarta.validation.constraints.NotNull;

/**
 * A save in a step's section, reported to rules-service so the setup checklist can tell it apart
 * from configuration that was already there before a restart.
 *
 * @param branchId the branch of a per-branch step ({@link OnboardingStep#perBranch()}); null otherwise
 */
public record OnboardingTouch(@NotNull OnboardingStep step, Long branchId) {}
