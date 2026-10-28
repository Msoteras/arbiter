package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.InsuredStatusResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystWorkloadResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AssignAnalystRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AssignedCaseSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.HomeSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseActionResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ClaimCauseCorrectionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ClaimCauseOption;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDeadline;
import ar.edu.utn.frba.arbiter.cases.dto.CaseFollowUp;
import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageInboxItemResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseScope;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CloseCaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.DocumentRequestRequest;
import ar.edu.utn.frba.arbiter.cases.dto.PendingDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.RequestableDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PendingSettlementResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AuthorizedSettlementResponse;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementReturnRequest;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementResponse;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckRequest;
import ar.edu.utn.frba.arbiter.cases.dto.IntakeDocumentsResponse;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckResponse;
import ar.edu.utn.frba.arbiter.cases.dto.LensSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ReopenCaseRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.services.CaseMessageService;
import ar.edu.utn.frba.arbiter.cases.services.CaseService;
import ar.edu.utn.frba.arbiter.cases.services.ClaimCauseCorrectionService;
import ar.edu.utn.frba.arbiter.cases.services.SettlementService;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.InsuredStatus;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
@Tag(name = "Expedientes", description = "Ciclo de vida de los expedientes")
public class CaseController {

    private final CaseService caseService;
    private final SettlementService settlementService;
    private final ClaimCauseCorrectionService claimCauseCorrectionService;
    private final CaseMessageService messageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ASEGURADO')")
    @Operation(summary = "Crear un expediente",
            description = """
                    Registra el expediente con la misma estructura que la denuncia y encola el análisis
                    en classification-service, con el monto reclamado y los adjuntos. Cada parte de
                    `documents` va con el código de lo que el documento ES (`police_report`,
                    `purchase_proof`, `repair_quote`, `item_photo`…).

                    Solo el asegurado denuncia, y solo en nombre propio: el `insuredId` tiene que ser
                    el DNI del token (si no, 403) y la póliza tiene que ser suya (si no, 422).
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
    @Operation(summary = "Verificar si la denuncia se aceptaría",
            description = """
                    Corre el mismo control que POST /cases (titularidad, vigencia, carencia y premio
                    impago) sin crear nada. El wizard lo llama apenas tiene póliza y fecha del hecho,
                    para frenar o avisar antes de que el asegurado complete el resto y suba la
                    documentación. Siempre responde 200: una póliza que no califica vuelve como
                    `{eligible: false, reason: "..."}`, no como error.
                    """)
    public ResponseEntity<EligibilityCheckResponse> checkEligibility(
            @RequestBody @Valid EligibilityCheckRequest request
    ) {
        return ResponseEntity.ok(caseService.checkEligibility(request));
    }

    @GetMapping("/intake-documents")
    @PreAuthorize("hasRole('ASEGURADO')")
    @Operation(summary = "Documentos que se piden al denunciar",
            description = "La primera tanda: lo que exige el Fast Track de la cobertura que responde por ese hecho "
                    + "generador. Si la aseguradora no configuró ninguno, devuelve la agenda documental entera "
                    + "(`fastTrackOnly=false`). La agenda completa se pide después, y solo si el expediente no "
                    + "entra en Fast Track. 503 si no se pudo leer el motor de reglas: una lista vacía se leería "
                    + "como \"no hacen falta documentos\".")
    public ResponseEntity<IntakeDocumentsResponse> intakeDocuments(
            @RequestParam String policyNumber,
            @RequestParam String branch,
            @RequestParam String claimCause) {
        return ResponseEntity.ok(caseService.intakeDocuments(policyNumber, branch, claimCause));
    }

    @GetMapping("/insured-statuses")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Estados que puede ver el asegurado",
            description = "Cada código con su etiqueta, para el filtro del asegurado (`insuredStatus` en el listado). "
                    + "Varios estados internos comparten uno.")
    public ResponseEntity<List<InsuredStatusResponse>> insuredStatuses() {
        return ResponseEntity.ok(caseService.insuredStatuses());
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Obtener un expediente",
            description = """
                    Devuelve el expediente, el resultado del análisis y el historial completo de estados
                    (cada movimiento con actor, motivo y fecha). Un asegurado solo ve los suyos:
                    CaseAccessPolicy.assertCanRead compara su DNI con el del expediente y, si no
                    coincide, responde 404. El analista y el referente ven todos los de su aseguradora.

                    El asegurado recibe una vista recortada (CaseResponse.forInsured): sin
                    clasificación, score de riesgo, análisis de imágenes, reglas evaluadas, póliza ni
                    lecturas de documentos. `status` viene nulo y `insuredStatus` trae lo que se le
                    informa; el historial solo tiene los movimientos que lo cambiaron, sin `reason` ni
                    `observation`. El listado recorta igual e ignora `status` y `riskBand` para él: su
                    filtro es `insuredStatus` (ver GET /cases/insured-statuses).
                    """)
    public ResponseEntity<CaseResponse> getCase(
            @PathVariable Long caseId,
            @RequestParam(required = false) String insurer
    ) {
        CaseResponse response = caseService.getCase(caseId, insurer);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Listar expedientes",
            description = """
                    Devuelve los expedientes paginados, del más nuevo al más viejo por defecto. Filtros
                    opcionales y combinables: `status`, `claimCause` (hecho generador),
                    `policyNumber`, `insuredId` y el rango `eventDateFrom`/`eventDateTo` (ISO
                    `yyyy-MM-dd`, inclusivo en los dos extremos) sobre la fecha del hecho. Paginado
                    estándar de Spring Data: `page`, `size`, `sort` (ej. `sort=eventDate,desc`).

                    `q` es una búsqueda libre (sin distinguir mayúsculas, por substring) sobre número de
                    expediente, número de póliza y asegurado (`insuredId`/`insuredName`). `riskBand`
                    filtra por banda del score de fraude (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).
                    `assignedToMe=true` deja los asignados al analista que consulta: la vista "Míos"
                    de la bandeja; sin él es "Todos". Todos los filtros se combinan con AND.

                    `assignedToMe` es una marca y no un id de analista porque ese id es propio del
                    esquema de cada aseguradora: quién es "yo" lo resuelve el backend con el token.
                    Para un rol sin perfil de analista en la aseguradora (el referente) no devuelve
                    nada, nunca todo. Dice "de quién es el expediente", no "qué puede ver el usuario":
                    eso ya lo acota el esquema de la aseguradora.

                    `deadline=DUE_SOON` deja los que tienen el indicador de plazo prendido
                    (`deadlinePriority != NONE`: 10 días o menos para el vencimiento del art. 56, o ya
                    vencidos). `deadline=OVERDUE` deja solo los vencidos, los mismos que cuenta como
                    vencidos el inicio.

                    `followUp=AWAITING_ANALYST` es la revisión del analista sin lo que ya espera al
                    referente: los expedientes que todavía tiene que decidir el analista.

                    `scope` filtra por ciclo de vida: `OPEN` (los estados no terminales), `CLOSED`
                    (APPROVED, REJECTED, LAPSED, PRESCRIBED, WITHDRAWN, VOIDED) o `ALL`. Por defecto
                    `ALL`: el corte es de la pantalla que pide, no del endpoint, porque el portal del
                    asegurado usa este mismo listado y tiene que seguir viendo sus siniestros
                    resueltos. Un expediente vencido sigue siendo `OPEN`.

                    `status` acepta varios valores (`?status=A&status=B`). `insurerId` solo aplica a un
                    asegurado con pólizas en más de una compañía, el único rol que lee expedientes de
                    varios esquemas; para analista y referente no hace nada.
                    """)
    public ResponseEntity<Page<CaseResponse>> listCases(
            @RequestParam(required = false) List<CaseStatus> status,
            @RequestParam(required = false) List<InsuredStatus> insuredStatus,
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
            @RequestParam(required = false) CaseDeadline deadline,
            @RequestParam(required = false) CaseFollowUp followUp,
            @RequestParam(required = false) Integer staleDays,
            @RequestParam(defaultValue = "ALL") CaseScope scope,
            @RequestParam(required = false) Long insurerId,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<CaseResponse> response = caseService.listCases(
                status, insuredStatus, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                analystId, assignedToMe, unassigned, fraudAlert, assigned, deadline, followUp, staleDays, scope,
                insurerId,
                pageable);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{caseId}/assign")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Asignar el expediente a un analista",
            description = """
                    El analista pasa a ser el dueño del expediente. Lo pueden hacer los dos roles: el
                    referente reparte el trabajo, y un analista puede tomar un expediente (o pasárselo
                    a un compañero) sin depender de él.

                    Un analista por expediente: si ya tenía uno, esta asignación lo reemplaza. Asignar
                    NO resuelve el expediente ni cambia su estado: sigue necesitando la decisión
                    explícita del analista (`POST /{id}/decision`). Cada asignación queda en el
                    historial.

                    `analystId` es el id de `claims_analyst`, que vive en el esquema de la aseguradora:
                    un analista de otra compañía no existe en esa tabla y responde 404.
                    """)
    public ResponseEntity<CaseResponse> assignAnalyst(
            @PathVariable Long caseId,
            @RequestBody @Valid AssignAnalystRequest request
    ) {
        return ResponseEntity.ok(caseService.assignAnalyst(caseId, request.analystId()));
    }

    @PostMapping("/{caseId}/reopen")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Reabrir un expediente cerrado",
            description = """
                    Devuelve a la bandeja del analista (PENDING_ANALYST_REVIEW) un expediente ya
                    cerrado, en cualquier estado terminal. Es la rehabilitación del procedimiento de
                    siniestros: sin ella, ni un error del analista ni la documentación que el
                    asegurado trae después de la caducidad se pueden corregir en el sistema.

                    Reabrir NO es un veredicto nuevo ni revierte el anterior: esa decisión existió y su
                    registro de auditoría es inmutable. Tampoco borra el riesgo ni el antecedente de
                    fraude. Solo vuelve a poner a una persona a cargo de decidir (decisión de
                    arquitectura #5), con el plazo del art. 56 desde cero.

                    `reason` es obligatorio: es la única explicación que queda en el historial. Lo
                    pueden hacer los dos roles. Desde un estado no terminal responde 409.
                    """)
    public ResponseEntity<CaseResponse> reopenCase(
            @PathVariable Long caseId,
            @RequestBody @Valid ReopenCaseRequest request
    ) {
        return ResponseEntity.ok(caseService.reopenCase(caseId, request.reason()));
    }

    @PostMapping("/{caseId}/closures")
    @PreAuthorize("hasRole('ANALISTA_SINIESTROS')")
    @Operation(summary = "Cerrar el expediente sin veredicto",
            description = """
                    Termina el expediente como WITHDRAWN (el asegurado abandonó el reclamo) o VOIDED
                    (la denuncia nunca debió existir: duplicada, sobre la póliza equivocada). Los dos
                    son terminales: el expediente no admite otro movimiento hasta que alguien lo
                    reabra (`POST /{id}/reopen`).

                    Solo el analista asignado, como una decisión. Se permite desde
                    PENDING_ANALYST_REVIEW, AWAITING_DOCUMENTATION y CLASSIFICATION_FAILED; desde
                    cualquier otro estado, 409. También 409 si la liquidación espera la firma del
                    referente. `reason` es obligatorio y va al historial. El asegurado ve "Cerrado",
                    nunca el motivo.
                    """)
    public ResponseEntity<CaseResponse> closeCase(
            @PathVariable Long caseId,
            @RequestBody @Valid CloseCaseRequest request
    ) {
        return ResponseEntity.ok(caseService.closeCase(caseId, request.closure().status(), request.reason()));
    }

    @DeleteMapping("/{caseId}/assign")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Liberar el expediente",
            description = """
                    Deja el expediente sin analista asignado. Sale de "Míos" de todos y sigue visible
                    en "Todos", listo para que otro lo tome. Idempotente: liberar uno que ya estaba
                    sin asignar no falla.
                    """)
    public ResponseEntity<CaseResponse> unassignAnalyst(@PathVariable Long caseId) {
        return ResponseEntity.ok(caseService.unassignAnalyst(caseId));
    }

    @GetMapping("/lens-summary")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Conteos de las vistas de la bandeja",
            description = """
                    Devuelve de una vez cuántos expedientes caen en cada combinación de ciclo de vida
                    (abiertos, cerrados, todos) y pertenencia (todos, míos, asignados, sin asignar,
                    alerta de fraude) para los filtros dados: los mismos del listado, menos `scope`,
                    que ya viene cruzado en la respuesta.

                    Existe para que la bandeja no pida una vista por llamada: lee la celda de la
                    combinación elegida, y cambiar de solapa no vuelve a consultar. Todo se cuenta en
                    una sola consulta, sin traer filas.

                    "Míos" es 0 para el referente, que no tiene perfil de analista en la aseguradora.
                    """)
    public ResponseEntity<LensSummaryResponse> lensSummary(
            @RequestParam(required = false) List<CaseStatus> status,
            @RequestParam(required = false) String claimCause,
            @RequestParam(required = false) String policyNumber,
            @RequestParam(required = false) String insuredId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDateTo,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) RiskBand riskBand,
            @RequestParam(required = false) Long analystId,
            @RequestParam(required = false) CaseFollowUp followUp,
            @RequestParam(required = false) Integer staleDays,
            @RequestParam(required = false) CaseDeadline deadline
    ) {
        return ResponseEntity.ok(caseService.lensSummary(
                status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                analystId, followUp, staleDays, deadline));
    }

    @GetMapping("/messages/inbox")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Todas las conversaciones de quien consulta",
            description = """
                    Analista y referente: una fila por expediente de la aseguradora con al menos un
                    mensaje. Asegurado: una fila por cada siniestro suyo, en todas sus aseguradoras,
                    tenga mensajes o no (con `insurerSlug`, porque los ids se repiten entre
                    compañías). Cada fila trae el último mensaje y los no leídos de quien consulta, del
                    más reciente al más viejo. La conversación completa está en
                    GET /cases/{caseId}/messages.

                    "No leídos" y "esperando respuesta" son vistas del front sobre estos mismos campos
                    (unreadCount y lastMessageSender), no parámetros de este endpoint.
                    """)
    public ResponseEntity<List<CaseMessageInboxItemResponse>> messagesInbox() {
        return ResponseEntity.ok(messageService.inbox());
    }

    @GetMapping("/analysts/workload")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Carga de trabajo por analista",
            description = """
                    Devuelve, para cada analista de la aseguradora, cuántos expedientes ACTIVOS (sin
                    resolver) tiene asignados. Alimenta el panel "Carga del equipo" del inicio del
                    referente.

                    Incluye a los analistas sin expedientes (en cero): la vista es del equipo entero.
                    Ordenado del más cargado al menos cargado. Solo el referente: es una vista de
                    gestión del equipo.
                    """)
    public ResponseEntity<List<AnalystWorkloadResponse>> analystWorkload() {
        return ResponseEntity.ok(caseService.analystWorkload());
    }

    @GetMapping("/home-summary")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Conteos del inicio",
            description = """
                    Todo lo que cuenta el inicio, en una sola llamada: los expedientes abiertos según
                    su plazo del art. 56 (en término, por vencer, vencidos, en pausa), los informes
                    recibidos del perito o del servicio técnico, las liquidaciones devueltas por el
                    referente o esperándolo, los expedientes sin movimiento en 15 días, las
                    clasificaciones fallidas y los sin asignar, con la fecha del más antiguo.

                    Para un analista cada conteo cubre sus expedientes abiertos, salvo los sin
                    asignar, que no son de nadie. Para el referente cubre toda la aseguradora.
                    """)
    public ResponseEntity<HomeSummaryResponse> homeSummary() {
        return ResponseEntity.ok(caseService.homeSummary());
    }

    @GetMapping("/assigned/summary")
    @PreAuthorize("hasRole('ANALISTA_SINIESTROS')")
    @Operation(summary = "Resumen de mis expedientes asignados",
            description = """
                    Devuelve el resumen de los expedientes asignados al analista logueado: total,
                    cantidad por estado y cuántos tienen alerta de fraude alta o crítica. Alimenta las
                    tarjetas de su inicio en una sola llamada.

                    Quién es "yo" lo resuelve el backend con el token: el id del analista es propio
                    del esquema de la aseguradora.
                    """)
    public ResponseEntity<AssignedCaseSummaryResponse> assignedCaseSummary() {
        return ResponseEntity.ok(caseService.assignedCaseSummary());
    }

    @GetMapping("/{caseId}/documents")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Listar los documentos de un expediente",
            description = """
                    Devuelve los datos de cada documento adjunto: tipo (el código de lo que el
                    documento representa, como `police_report` o `item_photo`), nombre del archivo,
                    tipo de contenido, tamaño en bytes y fecha de carga. NO devuelve el contenido: para
                    eso está la descarga de cada documento.

                    Al asegurado no le lista los informes de los proveedores (`expert_report`,
                    `repair_report`), y descargar uno responde 404.
                    """)
    public ResponseEntity<List<CaseDocumentResponse>> listDocuments(
            @PathVariable Long caseId,
            @RequestParam(required = false) String insurer
    ) {
        return ResponseEntity.ok(caseService.getDocuments(caseId, insurer));
    }

    @GetMapping("/{caseId}/documents/{documentId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Descargar un documento",
            description = "Devuelve el contenido de un documento adjunto al expediente.")
    public ResponseEntity<byte[]> downloadDocument(
            @PathVariable Long caseId,
            @PathVariable Long documentId,
            @RequestParam(required = false) String insurer
    ) {
        CaseDocument doc = caseService.getDocument(caseId, documentId, insurer);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.getContentType()))
                .header("Content-Disposition", "inline; filename=\"" + doc.getFilename() + "\"")
                .body(doc.getContent());
    }

    @PostMapping(value = "/{caseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ASEGURADO')")
    @Operation(summary = "Subir documentos adicionales",
            description = """
                    Suma documentos a un expediente existente y vuelve a encolar la clasificación. Cada
                    parte va con el código de lo que el documento ES (`police_report`,
                    `purchase_proof`, `repair_quote`, `item_photo`…).

                    Solo el asegurado dueño del expediente: pasa por el mismo control de titularidad
                    que las lecturas (404 sobre el expediente de otro).
                    """)
    public ResponseEntity<CaseResponse> uploadDocuments(
            @PathVariable Long caseId,
            @RequestParam Map<String, MultipartFile> documents,
            @RequestParam(required = false) String insurer
    ) {
        CaseResponse response = caseService.addDocumentsAndReclassify(caseId, documents, insurer);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{caseId}/pending-documents")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Documentos que faltan subir",
            description = """
                    Lo que el asegurado tiene que subir para que el expediente se vuelva a evaluar: lo
                    que pidió el analista, o si no pidió nada, lo que falta de la agenda documental.
                    `description` viene solo en un documento que el catálogo no nombra (`other_<n>`).
                    Vacío si el expediente no está esperando documentación. 503 si no se pudo leer la
                    agenda.
                    """)
    public ResponseEntity<List<PendingDocumentResponse>> pendingDocuments(
            @PathVariable Long caseId,
            @RequestParam(required = false) String insurer
    ) {
        return ResponseEntity.ok(caseService.pendingDocuments(caseId, insurer));
    }

    @GetMapping("/{caseId}/document-requests/options")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Documentos que se pueden pedir",
            description = """
                    Los documentos del ramo que todavía no se subieron ni se pidieron. `suggestion` dice por
                    qué los sugiere el sistema: `SCHEDULE` si los pide la agenda para todo expediente, `RISK`
                    si los pide desde la banda de riesgo del expediente; nulo si no los sugiere. Después de
                    un pedido, solo sugiere lo que pide una banda más alta que la de ese pedido. En Fast
                    Track no sugiere nada.
                    """)
    public ResponseEntity<List<RequestableDocumentResponse>> requestableDocuments(@PathVariable Long caseId) {
        return ResponseEntity.ok(caseService.requestableDocuments(caseId));
    }

    // @PreAuthorize only filters by role; the service also checks the caller is the assigned analyst.
    @PostMapping("/{caseId}/document-requests")
    @PreAuthorize("hasRole('ANALISTA_SINIESTROS')")
    @Operation(summary = "Pedir documentación al asegurado",
            description = """
                    Solo el analista asignado, solo desde PENDING_ANALYST_REVIEW y nunca en Fast Track
                    (400). El expediente pasa a
                    AWAITING_DOCUMENTATION: el plazo se congela y el asegurado recibe un mail. Cada
                    documento es un código del ramo, o `other` con `description` (lo que ve el
                    asegurado) y `reason` (por qué se pide; lo lee el modelo, nunca el asegurado).
                    Cuando el asegurado sube todo lo pedido, el expediente se vuelve a clasificar.
                    """)
    public ResponseEntity<CaseResponse> requestDocuments(
            @PathVariable Long caseId,
            @RequestBody @Valid DocumentRequestRequest request
    ) {
        return ResponseEntity.ok(caseService.requestDocuments(caseId, request));
    }

    @GetMapping("/{caseId}/claim-cause/options")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Hechos generadores a los que se puede corregir",
            description = "Los del mismo ramo que cubre alguna cobertura de la póliza, con la cobertura que respondería "
                    + "por cada uno. Excluye el actual.")
    public ResponseEntity<List<ClaimCauseOption>> claimCauseOptions(@PathVariable Long caseId) {
        return ResponseEntity.ok(claimCauseCorrectionService.options(caseId));
    }

    // @PreAuthorize only filters by role; the service also checks the caller is the assigned analyst.
    @PostMapping("/{caseId}/claim-cause")
    @PreAuthorize("hasRole('ANALISTA_SINIESTROS')")
    @Operation(summary = "Corregir el hecho generador declarado",
            description = """
                    Para cuando el relato contradice lo que declaró el asegurado (ej. "robo" para un
                    celular que se cayó). La cobertura no se elige a mano: sale del hecho generador,
                    con las mismas exclusiones que en el alta. El expediente vuelve a clasificarse,
                    porque las reglas, el Fast Track y la lectura del modelo se evaluaron contra la
                    cobertura anterior. Solo el analista asignado y solo en PENDING_ANALYST_REVIEW
                    (otro estado → 409); 422 si ninguna cobertura cubre el hecho nuevo o si la
                    liquidación espera al referente. El motivo queda en el historial; al asegurado no
                    se le avisa.
                    """)
    public ResponseEntity<CaseActionResponse> correctClaimCause(
            @PathVariable Long caseId,
            @RequestBody @Valid ClaimCauseCorrectionRequest request
    ) {
        claimCauseCorrectionService.correct(caseId, request);
        return ResponseEntity.accepted().body(new CaseActionResponse(caseId, "claim-cause-corrected"));
    }

    // The insurer lead may also retry: unblocking a stuck case is supervision, not a decision.
    @PostMapping("/{caseId}/retry-classification")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Reintentar la clasificación de un expediente fallido",
            description = """
                    Vuelve a encolar la clasificación de un expediente que quedó en
                    CLASSIFICATION_FAILED después de agotar los reintentos automáticos. Reinicia el
                    contador de intentos, lo pasa a PENDING_CLASSIFICATION y relanza el análisis con
                    la documentación ya subida. Solo desde CLASSIFICATION_FAILED (otro estado → 409).
                    No resuelve el expediente: sigue necesitando la decisión del analista (decisión de
                    arquitectura #5).
                    """)
    public ResponseEntity<CaseResponse> retryClassification(@PathVariable Long caseId) {
        return ResponseEntity.accepted().body(caseService.retryClassification(caseId));
    }

    // Scoped to the case, not a national ID lookup: the analyst only reaches the policies of someone whose
    // case they can already read.
    @GetMapping("/{caseId}/insured-policies")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Pólizas del asegurado del expediente",
            description = "Todas, en todas sus aseguradoras: la que se denuncia es solo parte del contexto.")
    public ResponseEntity<List<PolicyResponse>> getInsuredPolicies(@PathVariable Long caseId) {
        return ResponseEntity.ok(caseService.getInsuredPolicies(caseId));
    }

    // GET even with a parameter: it persists nothing and is recomputed as the analyst tries values.
    // The settlement is written by POST /decision.
    @GetMapping("/{caseId}/settlement")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Monto a pagar del siniestro",
            description = "La liquidación ya autorizada, o la propuesta que el analista tiene que confirmar: el tope, cada "
                    + "deducción con su motivo y el monto resultante. replacementValue muestra cómo quedaría con ese "
                    + "valor de reposición, sin liquidar nada.")
    public ResponseEntity<SettlementResponse> settlement(
            @PathVariable Long caseId,
            @RequestParam(required = false) BigDecimal replacementValue
    ) {
        return ResponseEntity.ok(settlementService.forCase(caseId, replacementValue));
    }

    @GetMapping("/settlements/pending-authorization")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Liquidaciones que esperan al referente",
            description = "Montos que un analista determinó por encima de la atribución de su ramo (Anexo II). De la más "
                    + "vieja a la más nueva: ese siniestro es el que más plazo legal de 30 días lleva consumido. "
                    + "Mientras tanto el expediente sigue en revisión del analista; el asegurado nunca ve este paso.")
    public ResponseEntity<List<PendingSettlementResponse>> pendingAuthorization() {
        return ResponseEntity.ok(settlementService.pendingAuthorization());
    }

    @GetMapping("/settlements/authorized")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Liquidaciones que autorizó el referente",
            description = "Los últimos 50 montos por encima de la atribución que firmó el referente, del más reciente al "
                    + "más viejo. Los que estaban dentro de la atribución del analista no aparecen: no los firmó nadie más.")
    public ResponseEntity<List<AuthorizedSettlementResponse>> authorizedSettlements() {
        return ResponseEntity.ok(settlementService.authorizedByReferent());
    }

    @PostMapping("/{caseId}/settlement/authorize")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Autorizar una liquidación por encima de la atribución del analista",
            description = "Recién acá la aprobación tiene efecto: se registra la decisión del analista con la "
                    + "justificación que dejó, y el expediente pasa a APPROVED, que es lo que le manda el mail al "
                    + "asegurado con el monto.")
    public ResponseEntity<CaseActionResponse> authorizeSettlement(@PathVariable Long caseId) {
        caseService.authorizeSettlement(caseId);
        return ResponseEntity.ok(new CaseActionResponse(caseId, "settlement-authorized"));
    }

    @PostMapping("/{caseId}/settlement/return")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Devolverle una liquidación al analista",
            description = "No es un rechazo del siniestro: el expediente nunca salió de la revisión del analista y no hay "
                    + "decisión registrada que deshacer. El analista vuelve a liquidar, con otro monto o el mismo "
                    + "mejor fundamentado.")
    public ResponseEntity<CaseActionResponse> returnSettlement(
            @PathVariable Long caseId,
            @RequestBody @Valid SettlementReturnRequest request
    ) {
        caseService.returnSettlement(caseId, request.reason());
        return ResponseEntity.ok(new CaseActionResponse(caseId, "settlement-returned"));
    }

    // Staff only: the justification is the analyst's reasoning, not what the insured is told.
    @GetMapping("/{caseId}/decision")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "La decisión de un expediente resuelto",
            description = "El veredicto del analista con su justificación y su fecha, leído del registro de auditoría. "
                    + "204 mientras el expediente no esté APPROVED ni REJECTED, también si se reabrió: su veredicto "
                    + "anterior queda en la auditoría pero ya no aplica.")
    public ResponseEntity<AnalystDecisionResponse> decision(@PathVariable Long caseId) {
        return caseService.getAnalystDecision(caseId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // @PreAuthorize only filters by role; the service also checks the caller is the assigned analyst.
    @PostMapping("/{caseId}/decision")
    @PreAuthorize("hasRole('ANALISTA_SINIESTROS')")
    @Operation(summary = "Registrar la decisión del analista",
            description = "Envía la decisión a classification-service para que quede en el registro de auditoría. Solo "
                    + "decide el analista asignado: 409 si todavía no tiene analista, 403 si es de otro. También 409 "
                    + "si la liquidación espera la firma del referente.")
    public ResponseEntity<CaseActionResponse> recordDecision(
            @PathVariable Long caseId,
            @RequestBody @Valid AnalystDecisionRequest request
    ) {
        caseService.recordAnalystDecision(caseId, request);
        return ResponseEntity.ok(new CaseActionResponse(caseId, "decision-recorded"));
    }
}