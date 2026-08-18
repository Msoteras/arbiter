package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystWorkloadResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AssignAnalystRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AssignedCaseSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckRequest;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckResponse;
import ar.edu.utn.frba.arbiter.cases.dto.LensSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.services.CaseService;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
@Tag(name = "Cases", description = "Case lifecycle management")
public class CaseController {

    private final CaseService caseService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ASEGURADO')")
    @Operation(summary = "Create a case",
            description = """
                    Registers a case using the same request structure as the claim flow and triggers
                    an analysis with classification-service, forwarding claimedAmount and any attached
                    documents. Each part under `documents` is keyed by what the document IS
                    (e.g. `police_report`, `invoice`, `quote`, `item_photo`).

                    Solo el ASEGURADO denuncia, y solo a nombre propio: el `insuredId` del payload
                    tiene que ser el DNI del token (403 si no), y la póliza denunciada tiene que ser
                    de ese asegurado (422 si no). El referente estaba habilitado acá por una
                    anotación más laxa que la regla de negocio — el frontend ya restringía la ruta
                    `new-claim` a ASEGURADO.
                    """)
    public ResponseEntity<CaseResponse> createCase(
            @RequestPart("case") @Valid CaseRequest request,
            @RequestParam(required = false) Map<String, MultipartFile> documents
    ) {
        CaseResponse response = caseService.createCase(request, documents);
        return ResponseEntity.accepted().body(response);
    }

    @PostMapping("/eligibility")
    @PreAuthorize("hasRole('ASEGURADO')")
    @Operation(summary = "Check whether a denuncia would be accepted",
            description = """
                    Runs the same intake gate as POST /cases (ownership, vigencia, carencia, mora)
                    without creating anything. The wizard calls this once it has a policy and an
                    event date, so it can block or warn before the insured fills out the rest of
                    the form and uploads documentation — instead of finding out only at the end.
                    Always 200; a non-eligible policy is `{eligible: false, reason: "..."}`, not an
                    error.
                    """)
    public ResponseEntity<EligibilityCheckResponse> checkEligibility(
            @RequestBody @Valid EligibilityCheckRequest request
    ) {
        return ResponseEntity.ok(caseService.checkEligibility(request));
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get case by id",
            description = """
                    Returns the stored case, its analysis result and the full status history
                    (every transition with actor, reason and timestamp). An ASEGURADO only sees
                    their own cases: CaseAccessPolicy.assertCanRead matches the caller's DNI against
                    the case's insured and 404s otherwise. Analyst and referente see every case in
                    their tenant.
                    """)
    public ResponseEntity<CaseResponse> getCase(
            @PathVariable Long caseId,
            @RequestParam(required = false) String aseguradora
    ) {
        CaseResponse response = caseService.getCase(caseId, aseguradora);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List cases",
            description = """
                    Devuelve expedientes paginados, más recientes primero por defecto. Filtros
                    opcionales y combinables: `status`, `claimCause` (tipo de siniestro /
                    HechoGenerador), `policyNumber`, `insuredId` (expedientes del asegurado —
                    hasta que Auth0/JWT integration lands, el caller lo pasa explícito) y el rango
                    `eventDateFrom`/`eventDateTo` (ISO `yyyy-MM-dd`, inclusive en ambos extremos)
                    sobre la fecha del hecho. Paginación estándar de Spring Data: `page`, `size`,
                    `sort` (ej. `sort=eventDate,desc`).

                    `q` es búsqueda de texto libre (case-insensitive, substring) sobre número de
                    expediente, número de póliza y asegurado (`insuredId`/`insuredName` — este
                    último nullable hasta que la primera clasificación resuelve el nombre real).
                    `riskBand` filtra por nivel de alerta de fraude (match exacto: `LOW`, `MEDIUM`,
                    `HIGH`, `CRITICAL`). `assignedToMe=true` acota a los expedientes asignados al
                    analista que hace el request — es la lente "Míos" de la bandeja; omitirlo es la
                    lente "Todos". Todos se combinan por AND entre sí.

                    `assignedToMe` es un flag y no un id de analista porque ese id es local al
                    esquema de cada aseguradora: quién es "yo" lo resuelve el backend contra el
                    token, no lo manda el cliente. Para un rol sin perfil de analista en el tenant
                    (el referente) la lente devuelve vacío, no todo.

                    Es "de quién es el expediente", no "qué expedientes puede ver este usuario":
                    lo segundo ya lo resuelve el esquema del tenant, que acota el listado a una
                    sola aseguradora.
                    """)
    public ResponseEntity<Page<CaseResponse>> listCases(
            @RequestParam(required = false) CaseStatus status,
            @RequestParam(required = false) String claimCause,
            @RequestParam(required = false) String policyNumber,
            @RequestParam(required = false) String insuredId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDateTo,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) RiskBand riskBand,
            @RequestParam(required = false) Long analystId,
            @RequestParam(defaultValue = "false") boolean assignedToMe,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(defaultValue = "false") boolean fraudAlert,
            @RequestParam(defaultValue = "false") boolean assigned,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<CaseResponse> response = caseService.listCases(
                status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                analystId, assignedToMe, unassigned, fraudAlert, assigned, pageable);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{caseId}/assign")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Asignar el expediente a un analista",
            description = """
                    Pone al analista como dueño del expediente. Habilitado para ambos roles: el
                    referente reparte trabajo y el analista puede tomar un expediente (o pasárselo
                    a un colega) sin depender de él.

                    Un solo analista por expediente: si ya tenía uno, esta asignación lo reemplaza.
                    Asignar NO resuelve ni mueve de estado — el expediente sigue necesitando la
                    decisión explícita del analista (`POST /{id}/decision`), que es lo que impacta
                    en el ciclo de vida. Cada (re)asignación queda registrada en el historial.

                    El `analystId` es el id de `claims_analyst`, que vive en el esquema de la
                    aseguradora: un analista de otra compañía no existe en esta tabla y da 404,
                    así que el aislamiento entre aseguradoras no necesita un chequeo aparte.
                    """)
    public ResponseEntity<CaseResponse> assignAnalyst(
            @PathVariable Long caseId,
            @RequestBody @Valid AssignAnalystRequest request
    ) {
        return ResponseEntity.ok(caseService.assignAnalyst(caseId, request.analystId()));
    }

    @DeleteMapping("/{caseId}/assign")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Liberar el expediente",
            description = """
                    Deja el expediente sin analista asignado. Queda fuera de la lente "Míos" de
                    todos y visible en "Todos", listo para que lo tome otro. Idempotente: liberar
                    un expediente que ya estaba sin asignar no falla.
                    """)
    public ResponseEntity<CaseResponse> unassignAnalyst(@PathVariable Long caseId) {
        return ResponseEntity.ok(caseService.unassignAnalyst(caseId));
    }

    @GetMapping("/lens-summary")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Conteos de las lentes de la bandeja",
            description = """
                    Devuelve de una sola vez cuántos expedientes hay en cada lente (todos, míos,
                    asignados, sin asignar, alerta de fraude) para los filtros que se pasen — los
                    mismos que acepta el listado.

                    Existe para no pedir una lente por request: eran cinco llamadas por cada cambio
                    de filtro, y cada una traía además una fila entera solo para leerle el total.
                    Acá se cuenta sin materializar filas.

                    "Míos" da 0 para el referente, que no tiene perfil de analista en el tenant.
                    """)
    public ResponseEntity<LensSummaryResponse> lensSummary(
            @RequestParam(required = false) CaseStatus status,
            @RequestParam(required = false) String claimCause,
            @RequestParam(required = false) String policyNumber,
            @RequestParam(required = false) String insuredId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDateTo,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) RiskBand riskBand,
            @RequestParam(required = false) Long analystId
    ) {
        return ResponseEntity.ok(caseService.lensSummary(
                status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand, analystId));
    }

    @GetMapping("/analysts/workload")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Carga de trabajo por analista",
            description = """
                    Devuelve, para cada analista de la aseguradora, cuántos expedientes ACTIVOS
                    (no resueltos) tiene asignados. Alimenta el panel "Carga del equipo" del inicio
                    del referente, que lo usa para repartir trabajo de un vistazo.

                    Incluye a los analistas sin expedientes asignados (con cero): la vista es del
                    equipo completo, no solo de los ocupados. Ordenado de más a menos cargado.

                    Solo el referente: es una vista de gestión del equipo. El recorte por aseguradora
                    lo resuelve el esquema del tenant, así que los conteos ya son de una sola compañía.
                    """)
    public ResponseEntity<List<AnalystWorkloadResponse>> analystWorkload() {
        return ResponseEntity.ok(caseService.analystWorkload());
    }

    @GetMapping("/assigned/summary")
    @PreAuthorize("hasRole('ANALISTA_SINIESTROS')")
    @Operation(summary = "Resumen de mis expedientes asignados",
            description = """
                    Devuelve el resumen de los expedientes asignados al analista logueado: total,
                    conteo por estado, y cuántos tienen alerta de fraude alta o crítica. Alimenta
                    las tarjetas de su pantalla de inicio en una sola llamada (en vez de un conteo
                    por estado a la vez).

                    Quién es "yo" lo resuelve el backend contra el token —el id de analista es local
                    al esquema de la aseguradora—, no lo manda el cliente.
                    """)
    public ResponseEntity<AssignedCaseSummaryResponse> assignedCaseSummary() {
        return ResponseEntity.ok(caseService.assignedCaseSummary());
    }

    @GetMapping("/{caseId}/documents")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List documents for a case",
            description = """
                    Returns metadata for every document attached to the case: type (what
                    the document represents — e.g. `police_report`, `invoice`, `item_photo`),
                    filename, content type, size in bytes, and upload timestamp. Does NOT
                    return the binary content — use the per-document download endpoint for that.
                    """)
    public ResponseEntity<List<CaseDocumentResponse>> listDocuments(
            @PathVariable Long caseId,
            @RequestParam(required = false) String aseguradora
    ) {
        return ResponseEntity.ok(caseService.getDocuments(caseId, aseguradora));
    }

    @GetMapping("/{caseId}/documents/{documentId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Download a document",
            description = "Returns the binary content of a specific document attached to the case.")
    public ResponseEntity<byte[]> downloadDocument(
            @PathVariable Long caseId,
            @PathVariable Long documentId,
            @RequestParam(required = false) String aseguradora
    ) {
        CaseDocument doc = caseService.getDocument(caseId, documentId, aseguradora);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.getContentType()))
                .header("Content-Disposition", "inline; filename=\"" + doc.getFilename() + "\"")
                .body(doc.getContent());
    }

    @PostMapping(value = "/{caseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ASEGURADO')")
    @Operation(summary = "Upload additional documents",
            description = """
                    Uploads additional documents to an existing case and re-triggers
                    classification. Each part is keyed by what the document IS
                    (e.g. `police_report`, `invoice`, `quote`, `item_photo`).

                    Solo el asegurado dueño del expediente: la carga pasa por el mismo control de
                    pertenencia que las lecturas (404 sobre un expediente ajeno). Antes era un
                    `findById` pelado, así que cualquier asegurado podía subir documentación al
                    expediente de otro y forzarle una reclasificación.
                    """)
    public ResponseEntity<CaseResponse> uploadDocuments(
            @PathVariable Long caseId,
            @RequestParam Map<String, MultipartFile> documents,
            @RequestParam(required = false) String aseguradora
    ) {
        CaseResponse response = caseService.addDocumentsAndReclassify(caseId, documents, aseguradora);
        return ResponseEntity.accepted().body(response);
    }

    @PostMapping("/{caseId}/retry-classification")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Reintentar la clasificación de un expediente fallido",
            description = """
                    Vuelve a poner en cola la clasificación de un expediente que quedó en
                    CLASSIFICATION_FAILED tras agotar los reintentos automáticos. El scheduler solo
                    barre PENDING_CLASSIFICATION, así que sin este empujón manual el caso queda
                    varado. Resetea el contador de intentos, lo devuelve a PENDING_CLASSIFICATION y
                    re-dispara el análisis con la documentación ya cargada. Solo válido desde
                    CLASSIFICATION_FAILED (otro estado → 409). No resuelve el caso: sigue necesitando
                    la decisión del analista (decisión de arquitectura #5).
                    """)
    public ResponseEntity<CaseResponse> retryClassification(@PathVariable Long caseId) {
        return ResponseEntity.accepted().body(caseService.retryClassification(caseId));
    }

    @PostMapping("/{caseId}/decision")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Persist the analyst's decision",
            description = "Forwards the analyst decision to classification-service so it is persisted in the audit trail.")
    public ResponseEntity<Map<String, Object>> recordDecision(
            @PathVariable Long caseId,
            @RequestBody @Valid AnalystDecisionRequest request
    ) {
        caseService.recordAnalystDecision(caseId, request);
        return ResponseEntity.ok(Map.of(
                "caseId", caseId,
                "status", "decision-recorded"
        ));
    }
}