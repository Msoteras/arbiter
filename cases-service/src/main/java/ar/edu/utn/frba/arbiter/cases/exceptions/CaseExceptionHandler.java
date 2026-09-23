package ar.edu.utn.frba.arbiter.cases.exceptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.UUID;

/**
 * Extends {@link ResponseEntityExceptionHandler} because of the {@code Exception} catch-all: advice is
 * consulted before Spring's own resolver, so without the parent, framework exceptions (bean validation,
 * malformed JSON, oversized uploads) would answer 500 instead of 400.
 */
@RestControllerAdvice
public class CaseExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CaseExceptionHandler.class);

    @ExceptionHandler(NotificationNotFoundException.class)
    public ProblemDetail handleNotificationNotFound(NotificationNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    @ExceptionHandler(CaseNotFoundException.class)
    public ProblemDetail handleNotFound(CaseNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    @ExceptionHandler(PolicyNotFoundException.class)
    public ProblemDetail handlePolicyNotFound(PolicyNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    @ExceptionHandler(CoverageNotFoundException.class)
    public ProblemDetail handleCoverageNotFound(CoverageNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ProblemDetail handleDocumentNotFound(DocumentNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    @ExceptionHandler(DocumentReadException.class)
    public ProblemDetail handleDocumentRead(DocumentReadException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ProblemDetail handleInvalidTransition(InvalidStatusTransitionException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(409), ex.getMessage());
    }

    @ExceptionHandler(UnresolvedCaseReferenceException.class)
    public ProblemDetail handleUnresolvedReference(UnresolvedCaseReferenceException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    @ExceptionHandler(UnknownCaseStateException.class)
    public ProblemDetail handleUnknownCaseState(UnknownCaseStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(500), ex.getMessage());
    }

    @ExceptionHandler(InvalidAnalystDecisionException.class)
    public ProblemDetail handleInvalidDecision(InvalidAnalystDecisionException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }

    @ExceptionHandler(InvalidSettlementException.class)
    public ProblemDetail handleInvalidSettlement(InvalidSettlementException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }

    @ExceptionHandler(InvalidRepairReportException.class)
    public ProblemDetail handleInvalidRepairReport(InvalidRepairReportException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }

    @ExceptionHandler(CaseNotAssignedException.class)
    public ProblemDetail handleCaseNotAssigned(CaseNotAssignedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(409), ex.getMessage());
    }

    @ExceptionHandler(CaseAssignedToAnotherAnalystException.class)
    public ProblemDetail handleCaseAssignedToAnotherAnalyst(CaseAssignedToAnotherAnalystException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(403), ex.getMessage());
    }

    @ExceptionHandler(AnalystNotFoundException.class)
    public ProblemDetail handleAnalystNotFound(AnalystNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    @ExceptionHandler(AnalystProfileNotFoundException.class)
    public ProblemDetail handleAnalystProfileNotFound(AnalystProfileNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(403), ex.getMessage());
    }

    @ExceptionHandler(InsuredIdentityMismatchException.class)
    public ProblemDetail handleInsuredIdentityMismatch(InsuredIdentityMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(403), ex.getMessage());
    }

    @ExceptionHandler(ExpertAssessmentNotFoundException.class)
    public ProblemDetail handleExpertAssessmentNotFound(ExpertAssessmentNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    @ExceptionHandler(ExpertFirmNotFoundException.class)
    public ProblemDetail handleExpertFirmNotFound(ExpertFirmNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    @ExceptionHandler(ClosedConversationException.class)
    public ProblemDetail handleClosedConversation(ClosedConversationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(409), ex.getMessage());
    }

    @ExceptionHandler(FraudRecordNotAllowedException.class)
    public ProblemDetail handleFraudRecordNotAllowed(FraudRecordNotAllowedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    @ExceptionHandler(DerivationNotAllowedException.class)
    public ProblemDetail handleDerivationNotAllowed(DerivationNotAllowedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    /** 503, not 422: an outage must not be presented to the analyst as the insurer's policy. */
    @ExceptionHandler(RulesUnavailableException.class)
    public ProblemDetail handleRulesUnavailable(RulesUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(503), ex.getMessage());
    }

    @ExceptionHandler(ExpertFirmInUseException.class)
    public ProblemDetail handleExpertFirmInUse(ExpertFirmInUseException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(409), ex.getMessage());
    }

    @ExceptionHandler(ExpertReportAlreadyReceivedException.class)
    public ProblemDetail handleExpertReportAlreadyReceived(ExpertReportAlreadyReceivedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(409), ex.getMessage());
    }

    @ExceptionHandler(PolicyInsuredMismatchException.class)
    public ProblemDetail handlePolicyInsuredMismatch(PolicyInsuredMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    @ExceptionHandler(MissingRequiredDocumentsException.class)
    public ProblemDetail handleMissingRequiredDocuments(MissingRequiredDocumentsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
        problem.setProperty("missingDocumentTypes", ex.getMissingDocumentTypes());
        return problem;
    }

    @ExceptionHandler(PolicyNotEligibleException.class)
    public ProblemDetail handlePolicyNotEligible(PolicyNotEligibleException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    /** Needed only because the catch-all below would otherwise turn every 403 into a 500. */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(403), "No tenés permiso para hacer esto.");
    }

    /**
     * The response carries only a reference code, never internals that could leak table names or
     * queries; the log carries the whole exception under that same code.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        String reference = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] Unhandled exception: {}", reference, ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(500),
                "Ocurrió un error inesperado. Si el problema persiste, pasale este código a soporte: "
                        + reference);
        problem.setTitle("Error interno");
        problem.setProperty("reference", reference);
        return problem;
    }
}
