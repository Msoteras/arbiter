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
     * {@code riskBand} (nivel de alerta de fraude, match exacto).
     *
     * <p>No filtra por aseguradora/rol del usuario autenticado: depende de auth-service, que
     * todavía no está levantado (ver GAPS-FLUJO.md, Gap F).
     */
    Page<CaseResponse> listCases(CaseStatus status, String claimCause, String policyNumber, String insuredId,
                                  LocalDate eventDateFrom, LocalDate eventDateTo, String q, RiskBand riskBand,
                                  Pageable pageable);

    CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents);

    void recordAnalystDecision(Long caseId, AnalystDecisionRequest request);
}