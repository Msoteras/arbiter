package ar.edu.utn.frba.arbiter.common.enums;

import java.util.List;

/**
 * Lifecycle state of an Expediente (case), owned by cases-service. Constants are in English
 * (code convention); the Spanish display label is a frontend concern (see estado.ts).
 */
public enum CaseStatus {
    /** Registered; classification requested but not yet available. Nobody acts; the system is working. */
    PENDING_CLASSIFICATION,
    /** Classification available; waiting for the analyst's decision. The ANALYST acts. */
    PENDING_ANALYST_REVIEW,
    /** Classification failed after exhausting retry attempts. Handling to be defined with the analysts. */
    CLASSIFICATION_FAILED,
    /**
     * Classification came back FALTA_DOCUMENTACION: required documents are missing. The INSURED
     * acts (uploads them via POST /cases/{id}/documents, which resets to PENDING_CLASSIFICATION).
     * Deliberately NOT in the analyst's queue.
     */
    AWAITING_DOCUMENTATION,
    /**
     * The analyst derived the case to an external expert and is waiting for their report. Not
     * final and not a verdict: the case comes back to {@link #PENDING_ANALYST_REVIEW} once the
     * report is in, and the analyst still decides. Nobody inside Arbiter acts meanwhile — the
     * expert is outside the system and answers by email.
     */
    PENDING_EXPERT_REPORT,
    PENDING_REPAIR,
    /** Final: the analyst approved the claim (set by the decision endpoint). */
    APPROVED,
    /** Final: the analyst rejected the claim (set by the decision endpoint). */
    REJECTED,
    /**
     * Final: closed by {@code LapseSweepScheduler} after 18 months with no movement from the
     * insured since the denuncia while stuck in {@link #AWAITING_DOCUMENTATION} — "inacción del
     * asegurado ante requerimientos" (regla interna, distinta de la prescripción legal). SYSTEM-driven,
     * never chosen by an analyst.
     */
    LAPSED;

    /**
     * Los estados en los que el plazo del art. 56 <b>no corre</b>, porque el expediente está
     * esperando a alguien de afuera de la aseguradora: al asegurado por documentación, al perito
     * por su informe, al servicio técnico por el equipo.
     *
     * <p>Es lo que dice el procedimiento de la compañía: pedir documentación adicional o derivar a
     * un estudio liquidador / servicio técnico "interrumpe el plazo para que la Aseguradora se
     * expida". Ese tiempo transcurre para el asegurado pero no se le imputa a la gestión.
     *
     * <p>Vive acá y no en cases-service porque lo responden dos módulos: cases-service congela con
     * esto la fecha límite del expediente, y reports-service lo usa para separar, en el tiempo
     * promedio de resolución, lo que tardó la compañía de lo que tardó esperando a un tercero. Dos
     * listas separadas se desincronizan, y el día que lo hagan el tablero y el semáforo de plazos
     * van a contradecirse sin que nadie lo note.
     */
    public static List<CaseStatus> pausingTheTerm() {
        return List.of(AWAITING_DOCUMENTATION, PENDING_EXPERT_REPORT, PENDING_REPAIR);
    }
}
