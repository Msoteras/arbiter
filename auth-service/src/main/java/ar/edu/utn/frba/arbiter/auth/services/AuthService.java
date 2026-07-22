package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.LoginRequest;
import ar.edu.utn.frba.arbiter.auth.dto.LoginResponse;
import ar.edu.utn.frba.arbiter.auth.models.entities.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final CredentialsAuthenticator credentialsAuthenticator;
    private final JwtService jwtService;

    public LoginResponse login(LoginRequest request) {
        User user = credentialsAuthenticator.authenticate(request.email(), request.password());
        JwtService.IssuedToken issuedToken = jwtService.issue(user);
        return new LoginResponse(
                issuedToken.token(),
                issuedToken.expiresAt(),
                user.getEmail(),
                user.getRol(),
                user.getNombre(),
                user.getApellido(),
                user.getInsuredId());
    }
}
