package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/** The checklist, in the order the referent sees it. */
public record OnboardingStatusDto(List<OnboardingStepDto> steps) {}
