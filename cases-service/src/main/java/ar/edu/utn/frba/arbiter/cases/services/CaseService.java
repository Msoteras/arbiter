package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Map;

public interface CaseService {

    CaseResponse createCase(CaseRequest request, Map<String, MultipartFile> documents);

    CaseResponse getCase(Long caseId);

    /**
     * Lista expedientes paginados, más recientes primero por defecto. Todos los filtros son
     * opcionales y combinables: {@code status}, {@code claimCause} (tipo de siniestro /
     * HechoGenerador), {@code policyNumber}, {@code insuredId} (hasta que Auth0/JWT lands, el
     * caller lo pasa explícito; después saldrá del JWT) y el rango {@code eventDateFrom}/
     * {@code eventDateTo} (inclusive en ambos extremos) sobre la fecha del hecho.
     *
     * <p>No filtra por aseguradora/rol del usuario autenticado: depende de auth-service, que
     * todavía no está levantado (ver GAPS-FLUJO.md, Gap F).
     */
    Page<CaseResponse> listCases(CaseStatus status, String claimCause, String policyNumber, String insuredId,
                                  LocalDate eventDateFrom, LocalDate eventDateTo, Pageable pageable);

    CaseResponse addDocumentsAndReclassify(Long caseId, Map<String, MultipartFile> documents);

    void recordAnalystDecision(Long caseId, AnalystDecisionRequest request);
}