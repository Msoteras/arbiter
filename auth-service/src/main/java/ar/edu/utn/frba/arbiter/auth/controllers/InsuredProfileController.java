package ar.edu.utn.frba.arbiter.auth.controllers;

import ar.edu.utn.frba.arbiter.auth.dto.LoginResponse;
import ar.edu.utn.frba.arbiter.auth.dto.OnboardingRequest;
import ar.edu.utn.frba.arbiter.auth.dto.ProfileResponse;
import ar.edu.utn.frba.arbiter.auth.dto.UpdateProfileRequest;
import ar.edu.utn.frba.arbiter.auth.exceptions.UserNotFoundException;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.auth.services.InsuredProfileService;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/profile")
@PreAuthorize("hasRole('ASEGURADO')")
@RequiredArgsConstructor
@Tag(name = "Insured Profile", description = "Onboarding and profile management for insured users")
public class InsuredProfileController {

    private final InsuredProfileService profileService;
    private final UserRepository userRepository;

    @GetMapping
    @Operation(summary = "Ver perfil del asegurado",
            description = """
                    Devuelve los datos del perfil del asegurado autenticado: nombre, DNI, contacto,
                    consentimientos y estado de onboarding. Lo usa tanto la pantalla de bienvenida
                    (para precargar datos) como la página de perfil.
                    """)
    public ResponseEntity<ProfileResponse> getProfile(Authentication authentication) {
        User user = resolveUser(authentication);
        return ResponseEntity.ok(profileService.getProfile(user));
    }

    @PostMapping("/onboarding")
    @Operation(summary = "Completar el onboarding de primer ingreso",
            description = """
                    El asegurado completa sus datos de contacto y da su consentimiento de análisis
                    de imágenes (H0009) la primera vez que entra. Solo se puede llamar una vez — si
                    ya completó el onboarding, devuelve 409. Los cambios posteriores van por PATCH
                    al perfil. Devuelve un JWT nuevo con onboardingComplete=true.
                    """)
    public ResponseEntity<LoginResponse> completeOnboarding(
            @RequestBody @Valid OnboardingRequest request, Authentication authentication) {
        User user = resolveUser(authentication);
        return ResponseEntity.ok(profileService.completeOnboarding(user, request));
    }

    @PatchMapping
    @Operation(summary = "Actualizar perfil y consentimientos",
            description = """
                    Actualización parcial del perfil del asegurado. Solo se actualizan los campos
                    que vengan en el body (null = no cambiar). Si cambia el consentimiento de
                    imágenes, se registra la versión del texto y el timestamp. Devuelve un JWT
                    nuevo por si cambió algo que viaja en el token.
                    """)
    public ResponseEntity<LoginResponse> updateProfile(
            @RequestBody @Valid UpdateProfileRequest request, Authentication authentication) {
        User user = resolveUser(authentication);
        return ResponseEntity.ok(profileService.updateProfile(user, request));
    }

    private User resolveUser(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new UserNotFoundException(-1L));
    }
}
