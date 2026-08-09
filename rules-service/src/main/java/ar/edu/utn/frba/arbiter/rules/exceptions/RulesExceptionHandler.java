package ar.edu.utn.frba.arbiter.rules.exceptions;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates rules-service domain exceptions to RFC 7807 ProblemDetail, per-module advice. */
@RestControllerAdvice
public class RulesExceptionHandler {

    @ExceptionHandler(BranchNotFoundException.class)
    public ProblemDetail handleBranchNotFound(BranchNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    @ExceptionHandler(InvalidRuleConfigurationException.class)
    public ProblemDetail handleInvalidConfiguration(InvalidRuleConfigurationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }
}
