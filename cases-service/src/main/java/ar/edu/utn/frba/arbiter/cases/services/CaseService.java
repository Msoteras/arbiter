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
     * {@code riskBand} (nivel de alerta de fraude, match exacto) y {@code assignedAnalystId}
     * (expedientes de un analista puntual — la lente "Míos" de la bandeja).
     *
     * <p>No filtra por aseguradora/rol del usuario autenticado: depende del esquema multi-tenant,
     * que todavía no está (ver GAPS-FLUJO.md, Gap F). {@code assignedAnalystId} es "de quién es",
     * no "qué puedo ver": son dos recortes distintos.
     */
    Page<CaseResponse> listCases(CaseStatus status, String claimCause, String policyNumber, String insuredId,
                                  LocalDate eventDateFrom, LocalDate eventDateTo, String q, RiskBand riskBand,
                                  Long assignedAnalystId, Pageable pageable);

    CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents);

    void recordAnalystDecision(Long caseId, AnalystDecisionRequest request);

    /**
     * Pone al analista como dueño del expediente. Un solo analista por expediente: si ya tenía
     * uno, esta asignación lo reemplaza. Asignar NO resuelve ni mueve de estado — el expediente
     * sigue necesitando la decisión explícita del analista (decisión de arquitectura #5).
     */
    CaseResponse assignAnalyst(Long caseId, Long analystId);

    /** Libera el expediente: vuelve a quedar sin dueño, visible en "Todos" y en ninguna lente "Míos". */
    CaseResponse unassignAnalyst(Long caseId);
}