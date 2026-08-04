package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.AuthProperties;
import ar.edu.utn.frba.arbiter.auth.exceptions.Auth0ProvisioningException;
import com.auth0.client.auth.AuthAPI;
import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.mgmt.users.User;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sincroniza el ciclo de vida del usuario en Auth0 (Management API, app Machine-to-Machine
 * autorizada con `create:users`/`delete:users`/`read:users`) con el alta y la baja locales —
 * así el analista queda de verdad habilitado o deshabilitado para loguearse. Un token M2M
 * fresco por llamada — no cachea, el volumen del proyecto no justifica la complejidad extra.
 */
@Component
@ConditionalOnProperty(prefix = "arbiter.auth", name = "provider", havingValue = "auth0")
@RequiredArgsConstructor
public class Auth0UserProvisioner {

    private final AuthAPI auth0ManagementAuthApi;
    private final AuthProperties properties;

    /** @return the Auth0 user id ("auth0|...") — {@code User.authSub}'s source of truth. */
    public String createUser(String email, String rawPassword) {
        try {
            ManagementAPI management = managementApi();

            User newUser = new User(properties.auth0().connection());
            newUser.setEmail(email);
            newUser.setPassword(rawPassword.toCharArray());
            newUser.setEmailVerified(true);

            return management.users().create(newUser).execute().getBody().getId();
        } catch (Auth0Exception e) {
            throw new Auth0ProvisioningException("crear", email, e);
        }
    }

    public void updatePassword(String email, String rawPassword) {
        try {
            ManagementAPI management = managementApi();

            List<User> matches = management.users().listByEmail(email, null).execute().getBody();
            if (matches.isEmpty()) {
                throw new Auth0ProvisioningException("actualizar la contraseña de", email,
                        new IllegalStateException("no existe en Auth0"));
            }

            User update = new User(properties.auth0().connection());
            update.setPassword(rawPassword.toCharArray());
            management.users().update(matches.get(0).getId(), update).execute();
        } catch (Auth0Exception e) {
            throw new Auth0ProvisioningException("actualizar la contraseña de", email, e);
        }
    }

    /** Si el usuario no existe en Auth0 (nunca se provisionó, o ya se borró), no hace nada. */
    public void deleteUser(String email) {
        try {
            ManagementAPI management = managementApi();

            List<User> matches = management.users().listByEmail(email, null).execute().getBody();
            if (matches.isEmpty()) {
                return;
            }

            management.users().delete(matches.get(0).getId()).execute();
        } catch (Auth0Exception e) {
            throw new Auth0ProvisioningException("borrar", email, e);
        }
    }

    private ManagementAPI managementApi() throws Auth0Exception {
        String accessToken = auth0ManagementAuthApi.requestToken("https://" + properties.auth0().domain() + "/api/v2/")
                .execute()
                .getBody()
                .getAccessToken();
        return new ManagementAPI(properties.auth0().domain(), accessToken);
    }
}
