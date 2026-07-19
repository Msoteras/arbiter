package ar.edu.utn.frba.arbiter.auth.exceptions;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(401), ex.getMessage());
    }

    @ExceptionHandler(AccountLockedException.class)
    public ProblemDetail handleAccountLocked(AccountLockedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(423), ex.getMessage());
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(409), ex.getMessage());
    }

    @ExceptionHandler(RoleNotAllowedException.class)
    public ProblemDetail handleRoleNotAllowed(RoleNotAllowedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(404), ex.getMessage());
    }

    @ExceptionHandler(CannotChangeOwnRoleException.class)
    public ProblemDetail handleCannotChangeOwnRole(CannotChangeOwnRoleException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }
}
