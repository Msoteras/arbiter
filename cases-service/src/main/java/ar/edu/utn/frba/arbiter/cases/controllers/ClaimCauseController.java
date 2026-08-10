package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Catálogo de hechos generadores (claim_cause) por ramo, para poblar el selector del alta de
 * denuncia del asegurado. Los hechos son distintos por ramo (Celulares tiene "Rotura accidental",
 * Tecnología tiene "Daño accidental", etc.), así que el wizard NO puede ofrecer una lista fija:
 * pide los del ramo de la póliza elegida. Por NOMBRE de ramo, que es lo que la póliza tiene a mano.
 * Es el mismo eje que valida {@code CaseReferenceResolver} al crear el caso, así que lo que ofrece
 * el selector siempre resuelve (no más 422 por elegir un hecho que no existe en el ramo).
 */
@RestController
@RequestMapping("/api/v1/claim-causes")
@RequiredArgsConstructor
@Tag(name = "Claim causes", description = "Hechos generadores por ramo")
public class ClaimCauseController {

    private final ClaimCauseRepository claimCauseRepository;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Hechos generadores de un ramo",
            description = "Nombres de los hechos generadores del ramo dado, para el selector del alta de "
                    + "denuncia. Ramo desconocido ⇒ lista vacía.")
    public List<String> byBranch(@RequestParam String branch) {
        return claimCauseRepository.findByBranch_NameOrderByNameAsc(branch).stream()
                .map(ClaimCause::getName)
                .toList();
    }
}
