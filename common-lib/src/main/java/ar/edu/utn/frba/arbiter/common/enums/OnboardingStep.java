package ar.edu.utn.frba.arbiter.common.enums;

/**
 * The steps of the referent's setup checklist ("Puesta en marcha"), in the order the checklist
 * lists them. Shared because cases-service and auth-service report saves of their own steps to
 * rules-service, which owns the checklist.
 */
public enum OnboardingStep {
    COVERAGES(Level.REQUIRED, true),
    ANALYSTS(Level.REQUIRED, false),
    INSURED(Level.REQUIRED, false),
    DOCUMENTS(Level.RECOMMENDED, false),
    SCORING(Level.RECOMMENDED, false),
    FAST_TRACK(Level.RECOMMENDED, false),
    PROVIDERS(Level.RECOMMENDED, false),
    AUTHORITIES(Level.RECOMMENDED, false),
    HARD_STOP(Level.OPTIONAL, false),
    FRAUD(Level.OPTIONAL, false),
    TARGET(Level.OPTIONAL, false),
    COMMON_EXCLUSIONS(Level.OPTIONAL, false),
    BUSINESS_RULES(Level.OPTIONAL, false);

    public enum Level { REQUIRED, RECOMMENDED, OPTIONAL }

    private final Level level;
    private final boolean perBranch;

    OnboardingStep(Level level, boolean perBranch) {
        this.level = level;
        this.perBranch = perBranch;
    }

    public Level level() {
        return level;
    }

    /** One checklist entry per branch instead of one for the insurer. */
    public boolean perBranch() {
        return perBranch;
    }

    /** A required step can't be marked as not used: without it no claim gets through. */
    public boolean skippable() {
        return level != Level.REQUIRED;
    }

    /** The {@code onboarding_step.step_key} of this step, with the branch for a per-branch step. */
    public String key(Long branchId) {
        return perBranch ? name() + ":" + branchId : name();
    }
}
