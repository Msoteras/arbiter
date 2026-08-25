package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.auth.dto.LoginRequest;
import ar.edu.utn.frba.arbiter.auth.dto.LoginResponse;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.models.entities.Role;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final CredentialsAuthenticator credentialsAuthenticator;
    private final JwtService jwtService;
    private final TenantResolver tenantResolver;
    private final TenantProfileService tenantProfileService;
    private final PasswordCipher passwordCipher;

    /**
     * There's no JWT yet at this point — nothing to resolve a tenant from — so this
     * resolves and sets {@link TenantContext} itself for the one profile lookup it needs,
     * then clears it. Every other authenticated endpoint gets its tenant from
     * TenantResolvingFilter instead.
     *
     * <p>The envelope is opened here and not in {@link CredentialsAuthenticator}: it belongs to how
     * the frontend ships the password, not to whoever validates credentials.
     */
    public LoginResponse login(LoginRequest request) {
        String password = passwordCipher.decrypt(request.password());
        User user = credentialsAuthenticator.authenticate(request.email(), password);
        return issueSessionFor(user);
    }

    /**
     * Issues a session for a user whose identity is already established — activation and
     * password reset land here too, right after they finish (see {@link UserService}), so the
     * person who just proved they own the mailbox and chose a password walks straight into the
     * app instead of being sent to a login screen to type the password they just set. For an
     * ASEGURADO activating for the first time that also means landing directly in onboarding: the
     * frontend routes by role off the token this returns, and {@code onboardingGuard} takes it
     * from there.
     *
     * <p>Not exposed as its own endpoint — only ever called from inside another flow that has
     * already verified who the user is (credentials here, an invite/reset token in
     * {@link UserService}).
     */
    public LoginResponse issueSessionFor(User user) {
        UserRole rol = user.getRoles().stream()
                .findFirst()
                .map(Role::getCode)
                .map(UserRole::valueOf)
                .orElseThrow(() -> new IllegalStateException("Usuario sin rol asignado: " + user.getEmail()));

        List<Long> insurerIds = tenantResolver.insurerIdsFor(user.getId());
        Optional<Insurer> primaryInsurer = tenantResolver.primaryInsurerFor(user.getId());

        String nombre = null;
        String apellido = null;
        String insuredId = null;
        Boolean onboardingComplete = null;
        String tenantSchema = primaryInsurer.map(Insurer::getSchemaName).orElse(null);

        if (tenantSchema != null) {
            TenantContext.set(tenantSchema);
            try {
                var profile = tenantProfileService.find(rol, user.getId());
                if (profile.isPresent()) {
                    nombre = profile.get().name();
                    apellido = profile.get().surname();
                    insuredId = profile.get().dni();
                    onboardingComplete = profile.get().onboardingComplete();
                }
            } finally {
                TenantContext.clear();
            }
        }

        JwtService.IssuedToken issuedToken = jwtService.issue(
                user, rol, nombre, apellido, insuredId, onboardingComplete, insurerIds, tenantSchema);

        return new LoginResponse(
                issuedToken.token(),
                issuedToken.expiresAt(),
                user.getId(),
                user.getEmail(),
                rol,
                nombre,
                apellido,
                insuredId,
                onboardingComplete);
    }
}
