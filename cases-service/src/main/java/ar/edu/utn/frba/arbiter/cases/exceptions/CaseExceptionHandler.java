package ar.edu.utn.frba.arbiter.cases.exceptions;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CaseExceptionHandler {

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

    /** La póliza denunciada existe, pero es de otro asegurado. */
    @ExceptionHandler(PolicyInsuredMismatchException.class)
    public ProblemDetail handlePolicyInsuredMismatch(PolicyInsuredMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }
}
