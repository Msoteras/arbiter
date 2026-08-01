package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.LoginRequest;
import ar.edu.utn.frba.arbiter.auth.dto.LoginResponse;
import ar.edu.utn.frba.arbiter.auth.models.entities.User;
import ar.edu.utn.frba.arbiter.auth.models.entities.UserInsurer;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserInsurerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final CredentialsAuthenticator credentialsAuthenticator;
    private final JwtService jwtService;
    private final UserInsurerRepository userInsurerRepository;

    public LoginResponse login(LoginRequest request) {
        User user = credentialsAuthenticator.authenticate(request.email(), request.password());
        List<Long> insurerIds = userInsurerRepository.findByUserId(user.getId()).stream()
                .map(UserInsurer::getInsurerId)
                .toList();
        JwtService.IssuedToken issuedToken = jwtService.issue(user, insurerIds);
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
