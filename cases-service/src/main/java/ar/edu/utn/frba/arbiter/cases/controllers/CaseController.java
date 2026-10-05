package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystWorkloadResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AssignAnalystRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AssignedCaseSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseActionResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ClaimCauseCorrectionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ClaimCauseOption;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseFollowUp;
import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageInboxItemResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseScope;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
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
@Tag(name = "Cases", description = "Case lifecycle management")
public class CaseController {

    private final CaseService caseService;
    private final SettlementService settlementService;
    private final ClaimCauseCorrectionService claimCauseCorrectionService;
    private final CaseMessageService messageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ASEGURADO')")
    @Operation(summary = "Create a case",
            description = """
                    Registers a case using the same request structure as the claim flow and triggers
                    an analysis with classification-service, forwarding claimedAmount and any attached
                    documents. Each part under `documents` is keyed by what the document IS
                    (e.g. `police_report`, `invoice`, `quote`, `item_photo`).

                    Only the insured files a claim, and only in their own name: the payload's
                    `insuredId` must be the token's national ID (403 otherwise), and the claimed policy must
                    belong to that insured (422 otherwise). The insurer lead used to be allowed here by
                    an annotation looser than the business rule — the frontend already restricted
                    the `new-claim` route to the insured role.
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
    @Operation(summary = "Check whether a claim would be accepted",
            description = """
                    Runs the same intake gate as POST /cases (ownership, policy in force, waiting
                    period, overdue premium)
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

    @GetMapping("/intake-documents")
    @PreAuthorize("hasRole('ASEGURADO')")
    @Operation(summary = "Documents requested when the claim is filed",
            description = "The first batch: what Fast Track requires for the coverage that responds "
                    + "to that claim cause. If the insurer configured none, returns the full document "
                    + "agenda (`fastTrackOnly=false`). The full agenda is requested later, and only if "
                    + "the claim doesn't qualify for Fast Track. 503 if the rules engine couldn't be "
                    + "read: an empty list would read as \"no documents needed\".")
    public ResponseEntity<IntakeDocumentsResponse> intakeDocuments(
            @RequestParam String policyNumber,
            @RequestParam String branch,
            @RequestParam String claimCause) {
        return ResponseEntity.ok(caseService.intakeDocuments(policyNumber, branch, claimCause));
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get case by id",
            description = """
                    Returns the stored case, its analysis result and the full status history
                    (every transition with actor, reason and timestamp). An insured only sees
                    their own cases: CaseAccessPolicy.assertCanRead matches the caller's national ID against
                    the case's insured and 404s otherwise. Analyst and insurer lead see every case in
                    their tenant.
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
    @Operation(summary = "List cases",
            description = """
                    Returns cases paginated, most recent first by default. Optional, combinable
                    filters: `status`, `claimCause` (claim type), `policyNumber`,
                    `insuredId` (the insured's cases — until the Auth0/JWT integration lands, the
                    caller passes it explicitly) and the `eventDateFrom`/`eventDateTo` range (ISO
                    `yyyy-MM-dd`, inclusive on both ends) over the event date. Standard Spring Data
                    paging: `page`, `size`, `sort` (e.g. `sort=eventDate,desc`).

                    `q` is a free-text search (case-insensitive, substring) over case number, policy
                    number and insured (`insuredId`/`insuredName` — the latter is nullable until the
                    first classification resolves the real name). `riskBand` filters by fraud risk
                    score (exact match: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`). `assignedToMe=true`
                    narrows to the cases assigned to the requesting analyst — the inbox's "Mine"
                    lens; omitting it is the "All" lens. All filters are ANDed together.

                    `assignedToMe` is a flag and not an analyst id because that id is local to each
                    insurer's schema: who "me" is gets resolved by the backend from the token, not
                    sent by the client. For a role with no analyst profile in the tenant (the
                    insurer lead) the lens returns nothing, not everything.

                    It means "whose case is this", not "which cases can this user see": the latter
                    is already handled by the tenant schema, which scopes the listing to a single
                    insurer.

                    `dueSoon=true` narrows to cases with an active deadline indicator
                    (`deadlinePriority != NONE`: 10 days or fewer until the art. 56 response
                    deadline, or already overdue) — the "Due soon" lens.

                    `scope` filters by lifecycle: `OPEN` (the five non-terminal statuses), `CLOSED`
                    (APPROVED/REJECTED/LAPSED) or `ALL`. Defaults to `ALL`: the cut belongs to the
                    screen asking for it, not to the endpoint — the insured's portal consumes this
                    same listing and must keep seeing their resolved claims. An overdue case is
                    still `OPEN`.

                    `status` accepts several values (`?status=A&status=B`): the insured's portal
                    filters by the three buckets they see ("In progress" spans four statuses), and
                    the bucket→statuses mapping belongs to the frontend, like the rest of the labels.

                    `insurerId` only applies to an insured with policies at more than one company,
                    the only role that reads cases from several schemas. For analyst and insurer lead it
                    does nothing: the tenant already scopes them to a single insurer.
                    """)
    public ResponseEntity<Page<CaseResponse>> listCases(
            @RequestParam(required = false) List<CaseStatus> status,
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
            @RequestParam(defaultValue = "false") boolean dueSoon,
            @RequestParam(required = false) CaseFollowUp followUp,
            @RequestParam(required = false) Integer staleDays,
            @RequestParam(defaultValue = "ALL") CaseScope scope,
            @RequestParam(required = false) Long insurerId,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<CaseResponse> response = caseService.listCases(
                status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                analystId, assignedToMe, unassigned, fraudAlert, assigned, dueSoon, followUp, staleDays, scope,
                insurerId,
                pageable);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{caseId}/assign")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Assign the case to an analyst",
            description = """
                    Makes the analyst the owner of the case. Allowed for both roles: the insurer lead
                    distributes work, and an analyst can take a case (or hand it to a colleague)
                    without depending on them.

                    One analyst per case: if it already had one, this assignment replaces them.
                    Assigning does NOT resolve the case or change its status — it still needs the
                    analyst's explicit decision (`POST /{id}/decision`), which is what drives the
                    lifecycle. Every (re)assignment is recorded in the history.

                    `analystId` is the `claims_analyst` id, which lives in the insurer's schema: an
                    analyst from another company doesn't exist in this table and yields 404, so
                    isolation between insurers needs no separate check.
                    """)
    public ResponseEntity<CaseResponse> assignAnalyst(
            @PathVariable Long caseId,
            @RequestBody @Valid AssignAnalystRequest request
    ) {
        return ResponseEntity.ok(caseService.assignAnalyst(caseId, request.analystId()));
    }

    @PostMapping("/{caseId}/reopen")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Reopen a closed case",
            description = """
                    Sends a case that was already closed — APPROVED, REJECTED or LAPSED — back to
                    the analyst's desk (PENDING_ANALYST_REVIEW). It is the "rehabilitation" step of
                    the claims procedure: without it the three terminal statuses are dead ends, and
                    neither an analyst's mistake nor documentation the insured brings after the case
                    lapsed can be fixed within the system.

                    Reopening is NOT a new verdict, nor does it revert the previous one: that
                    decision happened and its audit record is immutable. It doesn't clear the risk
                    or the fraud record either, which are facts of the claim. All it does is put a
                    person back in charge of deciding (architecture decision #5), with the art. 56
                    deadline restarting from zero: reopening to fix a mistake can't hand over a case
                    that is already overdue.

                    `reason` is required — it is the only explanation left in the history. Allowed
                    for both roles on the same grounds as assigning: reopening resolves nothing, and
                    correcting operations is as much the insurer lead's job as the analyst's. From a
                    non-terminal status it returns 409: there is nothing to reopen in a case that is
                    still open.
                    """)
    public ResponseEntity<CaseResponse> reopenCase(
            @PathVariable Long caseId,
            @RequestBody @Valid ReopenCaseRequest request
    ) {
        return ResponseEntity.ok(caseService.reopenCase(caseId, request.reason()));
    }

    @DeleteMapping("/{caseId}/assign")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Release the case",
            description = """
                    Leaves the case with no assigned analyst. It drops out of everyone's "Mine" lens
                    and stays visible under "All", ready for someone else to take. Idempotent:
                    releasing a case that was already unassigned doesn't fail.
                    """)
    public ResponseEntity<CaseResponse> unassignAnalyst(@PathVariable Long caseId) {
        return ResponseEntity.ok(caseService.unassignAnalyst(caseId));
    }

    @GetMapping("/lens-summary")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Inbox lens counts",
            description = """
                    Returns in one go how many cases fall into each combination of lifecycle (open,
                    closed, all) and ownership (all, mine, assigned, unassigned, fraud alert) for the
                    given filters — the same ones the listing accepts, minus `scope`: lifecycle is
                    already crossed in the response.

                    It exists so the inbox doesn't request one lens per call: it reads the cell for
                    the chosen combination, so switching tabs doesn't query again. Everything is
                    counted in a single query, without materializing rows.

                    "Mine" is 0 for the insurer lead, who has no analyst profile in the tenant.
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
            @RequestParam(required = false) Integer staleDays
    ) {
        return ResponseEntity.ok(caseService.lensSummary(
                status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q, riskBand,
                analystId, followUp, staleDays));
    }

    @GetMapping("/messages/inbox")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "All of the caller's conversations",
            description = """
                    Analyst and insurer lead: one row per tenant case with at least one message.
                    Insured: one row per own claim, across all their insurers, whether it has
                    messages or not (with `insurerSlug`, since ids repeat across companies). Each
                    row carries the last message and the caller's unread count, most recent first.
                    The full thread stays at GET /cases/{caseId}/messages.

                    "Unread" and "awaiting reply" are frontend lenses over these same fields
                    (unreadCount and lastMessageSender), not parameters of this endpoint.
                    """)
    public ResponseEntity<List<CaseMessageInboxItemResponse>> messagesInbox() {
        return ResponseEntity.ok(messageService.inbox());
    }

    @GetMapping("/analysts/workload")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Workload per analyst",
            description = """
                    Returns, for each of the insurer's analysts, how many ACTIVE (unresolved) cases
                    are assigned to them. Feeds the "Team workload" panel on the insurer lead's home,
                    used to distribute work at a glance.

                    Includes analysts with no assigned cases (at zero): the view covers the whole
                    team, not just the busy ones. Sorted from most to least loaded.

                    Referente only: it is a team management view. Scoping by insurer is handled by
                    the tenant schema, so the counts already belong to a single company.
                    """)
    public ResponseEntity<List<AnalystWorkloadResponse>> analystWorkload() {
        return ResponseEntity.ok(caseService.analystWorkload());
    }

    @GetMapping("/assigned/summary")
    @PreAuthorize("hasRole('ANALISTA_SINIESTROS')")
    @Operation(summary = "Summary of my assigned cases",
            description = """
                    Returns the summary of the cases assigned to the logged-in analyst: total, count
                    per status, and how many carry a high or critical fraud alert. Feeds the cards on
                    their home screen in a single call (instead of one count per status at a time).

                    Who "me" is gets resolved by the backend from the token — the analyst id is local
                    to the insurer's schema — not sent by the client.
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
            @RequestParam(required = false) String insurer
    ) {
        return ResponseEntity.ok(caseService.getDocuments(caseId, insurer));
    }

    @GetMapping("/{caseId}/documents/{documentId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Download a document",
            description = "Returns the binary content of a specific document attached to the case.")
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
    @Operation(summary = "Upload additional documents",
            description = """
                    Uploads additional documents to an existing case and re-triggers
                    classification. Each part is keyed by what the document IS
                    (e.g. `police_report`, `invoice`, `quote`, `item_photo`).

                    Only the insured who owns the case: the upload goes through the same ownership
                    check as reads (404 on someone else's case). It used to be a bare `findById`, so
                    any insured could upload documentation to another's case and force it to be
                    reclassified.
                    """)
    public ResponseEntity<CaseResponse> uploadDocuments(
            @PathVariable Long caseId,
            @RequestParam Map<String, MultipartFile> documents,
            @RequestParam(required = false) String insurer
    ) {
        CaseResponse response = caseService.addDocumentsAndReclassify(caseId, documents, insurer);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{caseId}/claim-cause/options")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Claim causes the case can be corrected to",
            description = "Those in the same branch covered by some coverage of the policy, with the "
                    + "coverage that would respond for each. Excludes the current one.")
    public ResponseEntity<List<ClaimCauseOption>> claimCauseOptions(@PathVariable Long caseId) {
        return ResponseEntity.ok(claimCauseCorrectionService.options(caseId));
    }

    // @PreAuthorize only filters by role; the service also checks the caller is the assigned analyst.
    @PostMapping("/{caseId}/claim-cause")
    @PreAuthorize("hasRole('ANALISTA_SINIESTROS')")
    @Operation(summary = "Correct the declared claim cause",
            description = """
                    For when the narrative contradicts what the insured declared (e.g. "theft" for a
                    phone that was dropped). The coverage isn't picked by hand: it follows from the
                    claim cause, with the same exclusions as at intake. The case goes back to
                    classification, because the rules, Fast Track and the model's reading were
                    evaluated against the old coverage. Only the assigned analyst and only in
                    PENDING_ANALYST_REVIEW (any other status → 409); 422 if no coverage covers the
                    new cause or if the settlement is waiting on the insurer lead. The reason stays in
                    the history; the insured isn't notified.
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
    @Operation(summary = "Retry classification of a failed case",
            description = """
                    Re-queues the classification of a case left in CLASSIFICATION_FAILED after the
                    automatic retries ran out. The scheduler only sweeps PENDING_CLASSIFICATION, so
                    without this manual nudge the case stays stuck. Resets the attempt counter, moves
                    it back to PENDING_CLASSIFICATION and re-triggers the analysis with the
                    documentation already uploaded. Only valid from CLASSIFICATION_FAILED (any other
                    status → 409). Doesn't resolve the case: it still needs the analyst's decision
                    (architecture decision #5).
                    """)
    public ResponseEntity<CaseResponse> retryClassification(@PathVariable Long caseId) {
        return ResponseEntity.accepted().body(caseService.retryClassification(caseId));
    }

    // Scoped to the case, not a national ID lookup: the analyst only reaches the policies of someone whose
    // case they can already read.
    @GetMapping("/{caseId}/insured-policies")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Policies of the case's insured",
            description = "All of them, across insurers — the one being claimed is only part of the context.")
    public ResponseEntity<List<PolicyResponse>> getInsuredPolicies(@PathVariable Long caseId) {
        return ResponseEntity.ok(caseService.getInsuredPolicies(caseId));
    }

    // GET even with a parameter: it persists nothing and is recomputed as the analyst tries values.
    // The settlement is written by POST /decision.
    @GetMapping("/{caseId}/settlement")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Amount to be paid on the claim",
            description = "The settlement already authorized, or the proposal for the analyst to confirm: "
                    + "the ceiling, every deduction with the reason behind it, and the resulting amount. "
                    + "replacementValue previews what accrediting one would do — it settles nothing.")
    public ResponseEntity<SettlementResponse> settlement(
            @PathVariable Long caseId,
            @RequestParam(required = false) BigDecimal replacementValue
    ) {
        return ResponseEntity.ok(settlementService.forCase(caseId, replacementValue));
    }

    @GetMapping("/settlements/pending-authorization")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Settlements waiting for the insurer lead",
            description = "Amounts an analyst determined that went over their branch's attribution "
                    + "(Annex II). Oldest first: that claim has been burning its 30-day legal window "
                    + "the longest. The cases stay in the analyst's review meanwhile — the insured "
                    + "never sees this step.")
    public ResponseEntity<List<PendingSettlementResponse>> pendingAuthorization() {
        return ResponseEntity.ok(settlementService.pendingAuthorization());
    }

    @GetMapping("/settlements/authorized")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Settlements the insurer lead authorized",
            description = "The last 50 amounts over the attribution that the insurer lead signed off, "
                    + "most recent first. Amounts within the analyst's own attribution aren't here: "
                    + "nobody else signed them.")
    public ResponseEntity<List<AuthorizedSettlementResponse>> authorizedSettlements() {
        return ResponseEntity.ok(settlementService.authorizedByReferent());
    }

    @PostMapping("/{caseId}/settlement/authorize")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Authorize a settlement over the analyst's attribution",
            description = "Only here does the approval take effect: the analyst's decision is recorded "
                    + "with the justification they left, and the case moves to APPROVED — which is what "
                    + "emails the insured with the amount.")
    public ResponseEntity<CaseActionResponse> authorizeSettlement(@PathVariable Long caseId) {
        caseService.authorizeSettlement(caseId);
        return ResponseEntity.ok(new CaseActionResponse(caseId, "settlement-authorized"));
    }

    @PostMapping("/{caseId}/settlement/return")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Send a settlement back to the analyst",
            description = "Not a rejection of the claim: the case never left the analyst's review, and "
                    + "no decision was recorded to undo. They settle it again, at another amount or the "
                    + "same one better argued.")
    public ResponseEntity<CaseActionResponse> returnSettlement(
            @PathVariable Long caseId,
            @RequestBody @Valid SettlementReturnRequest request
    ) {
        caseService.returnSettlement(caseId, request.reason());
        return ResponseEntity.ok(new CaseActionResponse(caseId, "settlement-returned"));
    }

    // @PreAuthorize only filters by role; the service also checks the caller is the assigned analyst.
    @PostMapping("/{caseId}/decision")
    @PreAuthorize("hasRole('ANALISTA_SINIESTROS')")
    @Operation(summary = "Persist the analyst's decision",
            description = "Forwards the analyst decision to classification-service so it is persisted in the audit "
                    + "trail. Only the analyst the case is assigned to may decide — 409 if it isn't assigned to "
                    + "anyone yet, 403 if it's assigned to someone else.")
    public ResponseEntity<CaseActionResponse> recordDecision(
            @PathVariable Long caseId,
            @RequestBody @Valid AnalystDecisionRequest request
    ) {
        caseService.recordAnalystDecision(caseId, request);
        return ResponseEntity.ok(new CaseActionResponse(caseId, "decision-recorded"));
    }
}