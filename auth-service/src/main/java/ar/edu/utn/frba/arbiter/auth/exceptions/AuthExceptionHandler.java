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

    /** 400 rather than 401: the credentials didn't fail, the request couldn't be read. */
    @ExceptionHandler(InvalidEncryptedPasswordException.class)
    public ProblemDetail handleInvalidEncryptedPassword(InvalidEncryptedPasswordException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
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

    @ExceptionHandler(CannotDeleteOwnAccountException.class)
    public ProblemDetail handleCannotDeleteOwnAccount(CannotDeleteOwnAccountException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }

    @ExceptionHandler(Auth0ProvisioningException.class)
    public ProblemDetail handleAuth0Provisioning(Auth0ProvisioningException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(502), ex.getMessage());
    }

    @ExceptionHandler(InvalidEmailDomainException.class)
    public ProblemDetail handleInvalidEmailDomain(InvalidEmailDomainException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }

    @ExceptionHandler(InvalidInviteTokenException.class)
    public ProblemDetail handleInvalidInviteToken(InvalidInviteTokenException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }

    @ExceptionHandler(InviteTokenExpiredException.class)
    public ProblemDetail handleInviteTokenExpired(InviteTokenExpiredException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }

    @ExceptionHandler(UserAlreadyActiveException.class)
    public ProblemDetail handleUserAlreadyActive(UserAlreadyActiveException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), ex.getMessage());
    }
}
