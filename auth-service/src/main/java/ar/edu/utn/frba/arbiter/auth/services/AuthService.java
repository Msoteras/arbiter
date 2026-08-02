package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.auth.dto.LoginRequest;
import ar.edu.utn.frba.arbiter.auth.dto.LoginResponse;
import ar.edu.utn.frba.arbiter.auth.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.auth.models.entities.Role;
import ar.edu.utn.frba.arbiter.auth.models.entities.User;
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

    /**
     * There's no JWT yet at this point — nothing to resolve a tenant from — so this
     * resolves and sets {@link TenantContext} itself for the one profile lookup it needs,
     * then clears it. Every other authenticated endpoint gets its tenant from
     * TenantResolvingFilter instead.
     */
    public LoginResponse login(LoginRequest request) {
        User user = credentialsAuthenticator.authenticate(request.email(), request.password());

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
        String tenantSchema = primaryInsurer.map(Insurer::getSchemaName).orElse(null);

        if (tenantSchema != null) {
            TenantContext.set(tenantSchema);
            try {
                var profile = tenantProfileService.find(rol, user.getId());
                if (profile.isPresent()) {
                    nombre = profile.get().name();
                    apellido = profile.get().surname();
                    insuredId = profile.get().dni();
                }
            } finally {
                TenantContext.clear();
            }
        }

        JwtService.IssuedToken issuedToken = jwtService.issue(
                user, rol, nombre, apellido, insuredId, insurerIds, tenantSchema);

        return new LoginResponse(
                issuedToken.token(),
                issuedToken.expiresAt(),
                user.getEmail(),
                rol,
                nombre,
                apellido,
                insuredId);
    }
}
