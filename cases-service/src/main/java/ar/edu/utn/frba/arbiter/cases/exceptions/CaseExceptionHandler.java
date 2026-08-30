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
 * Extends {@link ResponseEntityExceptionHandler} only because of the {@code Exception} catch-all at
 * the bottom. {@code @ControllerAdvice} is consulted <b>before</b> Spring's own
 * {@code DefaultHandlerExceptionResolver}, so a catch-all here would intercept the framework's own
 * exceptions too and answer 500 to things that are plainly the client's fault: a body that fails
 * bean validation, malformed JSON, a missing query param, an upload over the limit. The whole
 * negative-test battery for the denuncia form (@PastOrPresent on the event date, @NotBlank on seven
 * fields, files over 10 MB) would have gone from 400 to 500 without this.
 *
 * <p>The parent handles those and, since Spring 6, already answers them as RFC 7807
 * {@code ProblemDetail} — the same shape the domain handlers below use.
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

    /** El analista al que se quiere asignar el expediente no existe en esta aseguradora. */
    @ExceptionHandler(AnalystNotFoundException.class)
    public ProblemDetail handleAnalystNotFound(AnalystNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    /** Distinto del anterior: acá el que no tiene perfil de analista es quien hace el request. */
    @ExceptionHandler(AnalystProfileNotFoundException.class)
    public ProblemDetail handleAnalystProfileNotFound(AnalystProfileNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(403), ex.getMessage());
    }

    /** Denunciar a nombre de otro asegurado. */
    @ExceptionHandler(InsuredIdentityMismatchException.class)
    public ProblemDetail handleInsuredIdentityMismatch(InsuredIdentityMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(403), ex.getMessage());
    }

    /** El expediente nunca se derivó a peritaje. */
    @ExceptionHandler(ExpertAssessmentNotFoundException.class)
    public ProblemDetail handleExpertAssessmentNotFound(ExpertAssessmentNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    /** El perito elegido no existe, está inactivo, o no cubre el ramo del siniestro. */
    @ExceptionHandler(ExpertFirmNotFoundException.class)
    public ProblemDetail handleExpertFirmNotFound(ExpertFirmNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    /** Se venció la ventana para escribir en el expediente ya resuelto. */
    @ExceptionHandler(ClosedConversationException.class)
    public ProblemDetail handleClosedConversation(ClosedConversationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(409), ex.getMessage());
    }

    /** El expediente no está en condiciones de originar un antecedente de fraude. */
    @ExceptionHandler(FraudRecordNotAllowedException.class)
    public ProblemDetail handleFraudRecordNotAllowed(FraudRecordNotAllowedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    /** La regla de la aseguradora no habilita derivar este expediente. */
    @ExceptionHandler(DerivationNotAllowedException.class)
    public ProblemDetail handleDerivationNotAllowed(DerivationNotAllowedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    /**
     * No se pudo consultar el motor de reglas. 503 y no 422: no es que la aseguradora no derive,
     * es que nadie pudo saberlo — y presentarlo como política sería mentirle al analista.
     */
    @ExceptionHandler(RulesUnavailableException.class)
    public ProblemDetail handleRulesUnavailable(RulesUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(503), ex.getMessage());
    }

    /** El perito ya recibió derivaciones: se desactiva, no se borra. */
    @ExceptionHandler(ExpertFirmInUseException.class)
    public ProblemDetail handleExpertFirmInUse(ExpertFirmInUseException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(409), ex.getMessage());
    }

    /** El informe del peritaje ya había llegado: no se pisa. */
    @ExceptionHandler(ExpertReportAlreadyReceivedException.class)
    public ProblemDetail handleExpertReportAlreadyReceived(ExpertReportAlreadyReceivedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(409), ex.getMessage());
    }

    /** La póliza denunciada existe, pero es de otro asegurado. */
    @ExceptionHandler(PolicyInsuredMismatchException.class)
    public ProblemDetail handlePolicyInsuredMismatch(PolicyInsuredMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    /**
     * The event doesn't fall under a contract with coverage (outside the coverage window, within
     * the waiting period, or impossible dates). The wizard shows the detail as-is: it's written
     * for the insured.
     */
    @ExceptionHandler(PolicyNotEligibleException.class)
    public ProblemDetail handlePolicyNotEligible(PolicyNotEligibleException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    /**
     * Denied by {@code @PreAuthorize}. It needs a handler of its own <b>only</b> because of the
     * catch-all below: without this, {@code Exception.class} would swallow it and every 403 in the
     * module would answer 500 instead — the security tests (denunciar con token de referente, subir
     * documentación a un expediente ajeno) would go from "correctly rejected" to "server broke".
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(403), "No tenés permiso para hacer esto.");
    }

    /**
     * Anything not foreseen above. Without this, an unexpected failure reached the client as a bare
     * "Internal Server Error" with no body — nothing to tell the user, and nothing to grep for in
     * the logs either.
     *
     * <p>Two halves, and both matter. The response carries a <b>reference code</b> and no internals:
     * a stack trace or a raw message can leak table names, ids and query fragments to whoever
     * triggered the error. The log carries the <b>whole</b> exception under that same code, at ERROR
     * — a catch-all that answers politely and stays quiet is worse than the 500 it replaces, because
     * the bug stops being visible.
     *
     * <p>Deliberately last and deliberately broad: every exception that <i>is</i> foreseen has its
     * own handler above with its own status, and Spring picks the most specific one. If something
     * shows up here often, that's the signal it deserves a handler of its own.
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
