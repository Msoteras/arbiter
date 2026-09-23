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
     * The envelope is opened here and not in {@link CredentialsAuthenticator}: it's about how the
     * frontend ships the password, not about validating credentials.
     */
    public LoginResponse login(LoginRequest request) {
        String password = passwordCipher.decrypt(request.password());
        User user = credentialsAuthenticator.authenticate(request.email(), password);
        return issueSessionFor(user);
    }

    /**
     * Issues a session for a user whose identity is already verified (credentials, or an
     * invite/reset token in {@link UserService}). Never expose it as its own endpoint.
     *
     * <p>There's no JWT yet to resolve a tenant from, so this sets {@link TenantContext} itself
     * for the profile lookup and clears it afterwards.
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
