package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Con qué vuelve el servicio técnico. Columna propia y no {@code ExpertVerdict}: ese es vocabulario
 * de fraude —y un {@code FRAUD_CONFIRMED} le deja el antecedente al asegurado— así que mezclarlos
 * haría que una reparación pudiera disparar algo que nadie investigó.
 */
public enum RepairOutcome {
    REPAIRED,
    IRREPARABLE,
    QUOTE_SENT
}
