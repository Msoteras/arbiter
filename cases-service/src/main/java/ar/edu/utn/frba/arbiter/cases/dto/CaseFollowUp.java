package ar.edu.utn.frba.arbiter.cases.dto;

public enum CaseFollowUp {
    EXPERT_REPORT_RECEIVED,
    REPAIR_REPORT_RECEIVED,
    /** Either of the two above, counted once: what the home's "Informes recibidos" adds up. */
    PROVIDER_REPORT_RECEIVED,
    RETURNED_BY_REFERENT,
    AWAITING_REFERENT,
    /** Under the analyst's review and not yet sent to the referent: the decision is still theirs. */
    AWAITING_ANALYST,
    /** Classified as missing documentation: the analyst still has to choose what to ask for. */
    DOCUMENTATION_TO_CONFIRM
}
