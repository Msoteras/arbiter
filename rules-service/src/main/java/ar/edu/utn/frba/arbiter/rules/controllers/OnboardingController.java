package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.common.dto.OnboardingTouch;
import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import ar.edu.utn.frba.arbiter.rules.dto.OnboardingSkipRequest;
import ar.edu.utn.frba.arbiter.rules.dto.OnboardingStatusDto;
import ar.edu.utn.frba.arbiter.rules.services.OnboardingService;
import ar.edu.utn.frba.arbiter.rules.services.OnboardingTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The referent's setup checklist ("Puesta en marcha"). */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Puesta en marcha", description = "Pasos para que una aseguradora quede lista para operar")
public class OnboardingController {

    private final OnboardingService service;
    private final OnboardingTracker tracker;

    @GetMapping("/onboarding")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Estado de la puesta en marcha",
            description = """
                    Los pasos en el orden de la lista, cada uno con su nivel, su estado (PENDING,
                    DONE o SKIPPED) y el número que resume lo configurado. El estado sale de la
                    configuración: un paso queda completo cuando tiene datos. Después de un
                    reinicio, además tiene que haber un guardado posterior en su sección.
                    Responde 503 si cases-service no contesta.
                    """)
    public OnboardingStatusDto status() {
        return service.status();
    }

    @PutMapping("/onboarding/steps/{step}")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Descartar un paso o deshacer el descarte",
            description = "skipped=false lo deshace. Los pasos obligatorios responden 422.")
    public ResponseEntity<Void> setSkipped(@PathVariable OnboardingStep step,
                                           @RequestBody OnboardingSkipRequest request) {
        service.setSkipped(step, request.skipped());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/onboarding/resets")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Reiniciar la puesta en marcha",
            description = "Todos los pasos vuelven a pendiente y se deshacen los descartes. "
                    + "La configuración no cambia. Herramienta de prueba.")
    public ResponseEntity<Void> reset(Authentication authentication) {
        service.reset(authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/onboarding/touches")
    @PreAuthorize("authentication.authorities.isEmpty()")
    @Operation(summary = "[interno] Guardado en la sección de un paso",
            description = "Lo llaman cases-service y auth-service, solo con token de servicio, después "
                    + "de guardar la configuración de un paso que es suyo.")
    public ResponseEntity<Void> touch(@RequestBody @Valid OnboardingTouch touch) {
        tracker.touch(touch.step(), touch.branchId());
        return ResponseEntity.noContent().build();
    }
}
