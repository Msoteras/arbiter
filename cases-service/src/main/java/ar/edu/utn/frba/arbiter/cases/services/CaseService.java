package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
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

    CaseResponse getCase(Long caseId);

    /**
     * @param insurerSlug en cuál de las aseguradoras del asegurado buscar ({@code provincia}).
     *                    Sólo hace falta cuando es cliente de más de una: los ids de expediente se
     *                    repiten entre esquemas. Null resuelve contra el tenant del login, que es
     *                    lo que necesita el analista.
     */
    CaseResponse getCase(Long caseId, String insurerSlug);

    List<CaseDocumentResponse> getDocuments(Long caseId);

    CaseDocument getDocument(Long caseId, Long documentId);

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
     */
    Page<CaseResponse> listCases(CaseStatus status, String claimCause, String policyNumber, String insuredId,
                                  LocalDate eventDateFrom, LocalDate eventDateTo, String q, RiskBand riskBand,
                                  boolean assignedToMe, Pageable pageable);

    CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents);

    /**
     * Reintento manual de la clasificación para un expediente en {@code CLASSIFICATION_FAILED}. El
     * scheduler solo barre {@code PENDING_CLASSIFICATION}, así que un caso que agotó los reintentos
     * queda varado sin este empujón. Devuelve el caso a {@code PENDING_CLASSIFICATION} (reseteando
     * el contador de intentos, si no el scheduler lo re-marcaría fallido enseguida) y vuelve a
     * disparar el análisis con la documentación ya cargada. Lo dispara el analista, no el sistema:
     * la máquina de estados rechaza (409) el reintento desde cualquier otro estado.
     */
    CaseResponse retryClassification(Long caseId);

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
}