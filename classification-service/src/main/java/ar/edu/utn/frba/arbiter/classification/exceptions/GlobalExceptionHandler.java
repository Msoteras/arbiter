package ar.edu.utn.frba.arbiter.classification.exceptions;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidClassificationException.class)
    public ProblemDetail handleInvalidClassification(InvalidClassificationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatusCode.valueOf(422));
        problem.setTitle("Invalid classification");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(FraudRecordAlreadyExistsException.class)
    public ProblemDetail handleFraudRecordAlreadyExists(FraudRecordAlreadyExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatusCode.valueOf(409));
        problem.setTitle("Antecedente ya registrado");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(UnsupportedFraudRecordException.class)
    public ProblemDetail handleUnsupportedFraudRecord(UnsupportedFraudRecordException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatusCode.valueOf(422));
        problem.setTitle("Antecedente sin respaldo");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatusCode.valueOf(400));
        problem.setTitle("Invalid data");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatusCode.valueOf(400));
        problem.setTitle("Validation failed");
        String errors = ex.getBindingResult().getAllErrors().stream()
                .map(e -> e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation errors");
        problem.setDetail(errors);
        return problem;
    }
}
