package ar.edu.utn.frba.arbiter.auth.exceptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every handler logs before answering, so a rejected request can be told apart from one that
 * never arrived.
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        return warn(401, ex);
    }

    /** 400 rather than 401: the credentials didn't fail, the request couldn't be read. */
    @ExceptionHandler(InvalidEncryptedPasswordException.class)
    public ProblemDetail handleInvalidEncryptedPassword(InvalidEncryptedPasswordException ex) {
        return warn(400, ex);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ProblemDetail handleAccountLocked(AccountLockedException ex) {
        return warn(423, ex);
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        return warn(409, ex);
    }

    @ExceptionHandler(RoleNotAllowedException.class)
    public ProblemDetail handleRoleNotAllowed(RoleNotAllowedException ex) {
        return warn(400, ex);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        return warn(404, ex);
    }

    @ExceptionHandler(CannotChangeOwnRoleException.class)
    public ProblemDetail handleCannotChangeOwnRole(CannotChangeOwnRoleException ex) {
        return warn(400, ex);
    }

    @ExceptionHandler(CannotDeleteOwnAccountException.class)
    public ProblemDetail handleCannotDeleteOwnAccount(CannotDeleteOwnAccountException ex) {
        return warn(400, ex);
    }

    @ExceptionHandler(Auth0ProvisioningException.class)
    public ProblemDetail handleAuth0Provisioning(Auth0ProvisioningException ex) {
        log.error("[Auth] Auth0ProvisioningException: {}", ex.getMessage(), ex);
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(502), ex.getMessage());
    }

    @ExceptionHandler(InvalidEmailDomainException.class)
    public ProblemDetail handleInvalidEmailDomain(InvalidEmailDomainException ex) {
        return warn(400, ex);
    }

    @ExceptionHandler(InvalidInviteTokenException.class)
    public ProblemDetail handleInvalidInviteToken(InvalidInviteTokenException ex) {
        return warn(400, ex);
    }

    @ExceptionHandler(InviteTokenExpiredException.class)
    public ProblemDetail handleInviteTokenExpired(InviteTokenExpiredException ex) {
        return warn(400, ex);
    }

    @ExceptionHandler(UserAlreadyActiveException.class)
    public ProblemDetail handleUserAlreadyActive(UserAlreadyActiveException ex) {
        return warn(400, ex);
    }

    @ExceptionHandler(OnboardingAlreadyCompleteException.class)
    public ProblemDetail handleOnboardingAlreadyComplete(OnboardingAlreadyCompleteException ex) {
        return warn(409, ex);
    }

    @ExceptionHandler(InsuredProfileNotFoundException.class)
    public ProblemDetail handleInsuredProfileNotFound(InsuredProfileNotFoundException ex) {
        return warn(404, ex);
    }

    /** WARN, not ERROR: these are expected client-facing outcomes, not bugs. */
    private ProblemDetail warn(int status, Exception ex) {
        log.warn("[Auth] {} — {}", ex.getClass().getSimpleName(), ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), ex.getMessage());
    }
}
