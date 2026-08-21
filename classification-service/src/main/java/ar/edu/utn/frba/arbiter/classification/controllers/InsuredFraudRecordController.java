package ar.edu.utn.frba.arbiter.classification.controllers;

import ar.edu.utn.frba.arbiter.classification.services.InsuredFraudRecordService;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordRequest;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Internal REST API for the insured's fraud records — cases-service registers them here when the
 * analyst confirms one on a case, and reads them back for the analyst reviewing a later claim.
 *
 * <p>Module-to-module, same as {@link ClaimController}: the identity checks belong to the caller
 * (cases-service resolves the analyst from the token and validates the case), and this side owns
 * the record and what it means.
 */
@RestController
@RequestMapping("/api/v1/fraud-records")
@RequiredArgsConstructor
@Tag(name = "Antecedentes de fraude",
        description = "Antecedentes de fraude del asegurado (interno, módulo a módulo).")
public class InsuredFraudRecordController {

    private final InsuredFraudRecordService service;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Registrar un antecedente de fraude",
            description = """
                    Deja asentado que un expediente terminó en fraude determinado. Un expediente
                    puede originar un solo antecedente.

                    El `source` decide cuánto pesa: `EXPERT_BACKED` (exige `expertAssessmentId`)
                    puntúa en el scoring y puede vetar el Fast Track; `ANALYST_DECLARED` queda
                    visible para quien revise la próxima denuncia y no interviene en el motor.
                    """,
            responses = {
                    @ApiResponse(responseCode = "201", description = "Antecedente registrado"),
                    @ApiResponse(responseCode = "409", description = "El expediente ya tiene antecedente"),
                    @ApiResponse(responseCode = "422", description = "Con respaldo pericial pero sin peritaje")
            }
    )
    public ResponseEntity<FraudRecordResponse> register(@RequestBody @Valid FraudRecordRequest request) {
        FraudRecordResponse created = service.register(request);
        return ResponseEntity
                .created(URI.create("/api/v1/fraud-records/insured/" + created.insuredDni()))
                .body(created);
    }

    @GetMapping("/insured/{insuredDni}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Antecedentes de un asegurado",
            description = "Devuelve también los vencidos, marcados con `inForce=false`: que hubo un "
                    + "antecedente y ya no cuenta no es lo mismo que que no haya habido ninguno.")
    public List<FraudRecordResponse> byInsured(@PathVariable String insuredDni) {
        return service.findByInsured(insuredDni);
    }
}
