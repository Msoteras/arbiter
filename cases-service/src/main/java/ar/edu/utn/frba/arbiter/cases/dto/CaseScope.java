package ar.edu.utn.frba.arbiter.cases.dto;

/** Lifecycle slice of the case list: "open" is five statuses and {@code status} only takes one. */
public enum CaseScope {
    OPEN,
    CLOSED,
    ALL
}
