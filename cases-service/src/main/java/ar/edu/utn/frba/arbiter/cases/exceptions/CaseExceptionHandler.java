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

    @ExceptionHandler(AnalystProfileNotFoundException.class)
    public ProblemDetail handleAnalystProfileNotFound(AnalystProfileNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(403), ex.getMessage());
    }
}
