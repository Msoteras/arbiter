package ar.edu.utn.frba.arbiter.cases.exceptions;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CaseExceptionHandler {

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
}
