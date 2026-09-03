package ar.edu.utn.frba.arbiter.rules.dto;

/**
 * Which audit table a history entry came from. The referente's history is one chronological feed,
 * but two append-only tables back it: rules and scoring are configured separately and version
 * separately, so their trails were never going to be one table.
 */
public enum RuleChangeSource {

    /** {@code insurer_rule_history} — Fast Track, exclusions, hard rules, free-text rules. */
    INSURER_RULE,

    /** {@code scoring_configuration_history} — factor weights and risk bands. */
    SCORING
}
