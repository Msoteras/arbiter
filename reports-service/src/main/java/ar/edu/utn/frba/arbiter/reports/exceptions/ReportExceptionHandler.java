package ar.edu.utn.frba.arbiter.reports.exceptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Extends {@link ResponseEntityExceptionHandler} so binding errors (missing {@code from}, unparsable
 * date, unknown {@code format}) also answer as a 400 {@code ProblemDetail}, like the domain errors.
 */
@RestControllerAdvice
public class ReportExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportExceptionHandler.class);

    @ExceptionHandler(InvalidReportPeriodException.class)
    public ProblemDetail handleInvalidPeriod(InvalidReportPeriodException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }

    @ExceptionHandler(UnknownBranchException.class)
    public ProblemDetail handleUnknownBranch(UnknownBranchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }

    @ExceptionHandler(TenantNotResolvedException.class)
    public ProblemDetail handleTenantNotResolved(TenantNotResolvedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(403), ex.getMessage());
    }

    @ExceptionHandler(ReportGenerationException.class)
    public ProblemDetail handleGeneration(ReportGenerationException ex) {
        log.error("[Reports] {}", ex.getMessage(), ex);
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(500), ex.getMessage());
    }
}
