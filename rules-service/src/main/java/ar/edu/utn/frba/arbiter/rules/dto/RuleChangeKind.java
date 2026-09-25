package ar.edu.utn.frba.arbiter.rules.dto;

/** What a history entry records: the rule coming into existence, or a later edit to it. */
public enum RuleChangeKind {

    /** The rule's first version. Carries no field changes: there was nothing before to compare. */
    CREATED,

    /** A save that changed at least one field. */
    UPDATED
}
