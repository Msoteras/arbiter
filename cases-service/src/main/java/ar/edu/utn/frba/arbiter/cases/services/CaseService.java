package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystWorkloadResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AssignedCaseSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckRequest;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckResponse;
import ar.edu.utn.frba.arbiter.cases.dto.LensSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface CaseService {

    CaseResponse createCase(CaseRequest request, Map<String, MultipartFile> documents);

    /**
     * Same gate {@link #createCase} runs before it builds the {@code Case} (ownership, vigencia,
     * carencia, mora), without creating anything. Lets the wizard block or warn before the insured
     * fills out the rest of the form and uploads documentation, instead of finding out only at the
     * very end. Never throws {@code PolicyNotEligibleException}/{@code PolicyInsuredMismatchException} —
     * those become {@code eligible=false} instead, since a "you can't file this" isn't an error here.
     */
    EligibilityCheckResponse checkEligibility(EligibilityCheckRequest request);

    CaseResponse getCase(Long caseId);

    /**
     * @param insurerSlug en cuál de las aseguradoras del asegurado buscar ({@code provincia}).
     *                    Sólo hace falta cuando es cliente de más de una: los ids de expediente se
     *                    repiten entre esquemas. Null resuelve contra el tenant del login, que es
     *                    lo que necesita el analista.
     */
    CaseResponse getCase(Long caseId, String insurerSlug);

    List<CaseDocumentResponse> getDocuments(Long caseId);

    /** @param insurerSlug misma razón que {@link #getCase(Long, String)}: los ids de expediente se repiten entre esquemas. */
    List<CaseDocumentResponse> getDocuments(Long caseId, String insurerSlug);

    CaseDocument getDocument(Long caseId, Long documentId);

    CaseDocument getDocument(Long caseId, Long documentId, String insurerSlug);

    /**
     * Lista expedientes paginados, más recientes primero por defecto. Todos los filtros son
     * opcionales y combinables: {@code status}, {@code claimCause} (tipo de siniestro /
     * HechoGenerador), {@code policyNumber}, {@code insuredId} (hasta que Auth0/JWT lands, el
     * caller lo pasa explícito; después saldrá del JWT), el rango {@code eventDateFrom}/
     * {@code eventDateTo} (inclusive en ambos extremos) sobre la fecha del hecho, {@code q}
     * (búsqueda de texto libre por número de expediente, póliza o asegurado — ver
     * {@link ar.edu.utn.frba.arbiter.cases.models.repositories.CaseSpecifications#withFilters}) y
     * {@code riskBand} (nivel de alerta de fraude, match exacto) y {@code assignedToMe}
     * (la lente "Míos" de la bandeja: solo los expedientes del analista que hace el request).
     *
     * <p>El recorte por aseguradora no es un filtro más: lo resuelve el esquema del tenant, así
     * que todo lo que se lista acá ya pertenece a una sola compañía. {@code assignedToMe} es
     * "de quién es el expediente", que es otra pregunta.
     *
     * <p>Es un booleano y no un id porque el id de analista es local al esquema: quién es "yo"
     * se resuelve acá contra el token, no lo manda el frontend. Para un rol sin perfil de
     * analista en el tenant (el referente) la lente devuelve vacío, no todo.
     *
     * <p>{@code analystId} es el filtro "Analista" del referente, y sí viaja del frontend: el id sale
     * de la lista que le dio {@code /analysts/workload}, y el esquema del tenant ya acota a su
     * aseguradora. Es distinto de {@code assignedToMe}, que resuelve "yo" contra el token.
     *
     * <p>{@code unassigned} (lente "Sin asignar": expedientes sin analista), {@code assigned}
     * (lente "Asignados": con analista, la bandeja del referente) y {@code fraudAlert} (lente
     * "Alerta de fraude": riesgo HIGH/CRITICAL) son las otras lentes de la bandeja. A diferencia de
     * {@code assignedToMe}, no dependen del "yo": son filtros booleanos puros.
     *
     * <p>{@code dueSoon} (lente "Por vencer": {@code deadlinePriority != NONE}, ver
     * {@link ar.edu.utn.frba.arbiter.common.enums.DeadlinePriority}) es otro filtro booleano puro,
     * combinable con el resto igual que {@code unassigned}/{@code assigned}/{@code fraudAlert}.
     */
    Page<CaseResponse> listCases(CaseStatus status, String claimCause, String policyNumber, String insuredId,
                                  LocalDate eventDateFrom, LocalDate eventDateTo, String q, RiskBand riskBand,
                                  Long analystId, boolean assignedToMe, boolean unassigned, boolean fraudAlert,
                                  boolean assigned, boolean dueSoon, Pageable pageable);

    /** Overload para las lentes "Míos"/"Todos" (sin las lentes de asignación, fraude ni vencimiento). */
    default Page<CaseResponse> listCases(CaseStatus status, String claimCause, String policyNumber, String insuredId,
                                          LocalDate eventDateFrom, LocalDate eventDateTo, String q, RiskBand riskBand,
                                          boolean assignedToMe, Pageable pageable) {
        return listCases(status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                null, assignedToMe, false, false, false, false, pageable);
    }

    /**
     * Los cinco conteos de las lentes de una sola vez, sobre los mismos filtros que el listado.
     * Cuenta con {@code count(spec)}: no trae filas ni joinea el análisis, a diferencia de pedir
     * cada lente con {@code size=1} solo para leer el total.
     */
    LensSummaryResponse lensSummary(CaseStatus status, String claimCause, String policyNumber,
                                     String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo,
                                     String q, RiskBand riskBand, Long analystId);

    CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents);

    /** @param insurerSlug misma razón que {@link #getCase(Long, String)}: los ids de expediente se repiten entre esquemas. */
    CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents, String insurerSlug);

    /**
     * Reintento manual de la clasificación para un expediente en {@code CLASSIFICATION_FAILED}. El
     * scheduler solo barre {@code PENDING_CLASSIFICATION}, así que un caso que agotó los reintentos
     * queda varado sin este empujón. Devuelve el caso a {@code PENDING_CLASSIFICATION} (reseteando
     * el contador de intentos, si no el scheduler lo re-marcaría fallido enseguida) y vuelve a
     * disparar el análisis con la documentación ya cargada. Lo dispara el analista, no el sistema:
     * la máquina de estados rechaza (409) el reintento desde cualquier otro estado.
     */
    CaseResponse retryClassification(Long caseId);

    /**
     * Every policy the case's insured holds, not just the one being claimed. Scoped to the case on
     * purpose: the analyst gets the context of whoever they're reviewing, not a lookup by DNI.
     */
    List<PolicyResponse> getInsuredPolicies(Long caseId);

    void recordAnalystDecision(Long caseId, AnalystDecisionRequest request);

    /**
     * Pone al analista como dueño del expediente, por su id de {@code claims_analyst}. Un solo
     * analista por expediente: si ya tenía uno, esta asignación lo reemplaza. Asignar NO resuelve
     * ni mueve de estado — el expediente sigue necesitando la decisión explícita del analista
     * (decisión de arquitectura #5).
     *
     * <p>El analista se busca en el esquema del tenant activo, así que un id de otra aseguradora
     * no resuelve y termina en 404.
     */
    CaseResponse assignAnalyst(Long caseId, Long analystId);

    /** Libera el expediente: vuelve a quedar sin dueño, visible en "Todos" y en ninguna lente "Míos". */
    CaseResponse unassignAnalyst(Long caseId);

    /**
     * Reabre un expediente cerrado y lo devuelve al escritorio del analista
     * ({@code PENDING_ANALYST_REVIEW}). Es la "rehabilitación" del doc de dominio BBVA: sin esto
     * los tres estados terminales son callejones sin salida y el error de un analista —o la
     * documentación que el asegurado trae después de que el expediente caducó— no tiene arreglo
     * dentro del sistema.
     *
     * <p>Reabrir NO es un veredicto nuevo: no toca la decisión anterior (que quedó en el registro
     * inmutable de clasificación, y sí ocurrió) ni el riesgo ni el antecedente de fraude. Solo
     * vuelve a poner a una persona a decidir, coherente con la decisión de arquitectura #5. El
     * {@code reason} es obligatorio porque es la única explicación que va a quedar en el historial.
     *
     * <p>Desde un estado no terminal la máquina de estados lo rechaza con 409 — no hay nada que
     * reabrir en un expediente que sigue abierto.
     */
    CaseResponse reopenCase(Long caseId, String reason);

    /**
     * Carga de trabajo del equipo: cada analista del tenant con su cantidad de expedientes activos
     * (no resueltos) asignados. Incluye a los analistas sin expedientes, con cero. Ordenado de más
     * a menos cargado. Es la vista que usa el referente para repartir trabajo.
     */
    List<AnalystWorkloadResponse> analystWorkload();

    /**
     * Resumen de los expedientes asignados al analista logueado (conteo por estado + total + cuántos
     * de riesgo alto/crítico), para las tarjetas de su inicio. El "yo" se resuelve contra el token;
     * un rol sin perfil de analista recibe el resumen vacío.
     */
    AssignedCaseSummaryResponse assignedCaseSummary();
}