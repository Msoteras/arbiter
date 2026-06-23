package ar.edu.utn.frba.arbiter.siniestros.controllers;

import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;
import ar.edu.utn.frba.arbiter.siniestros.dto.DenunciaSiniestro;
import ar.edu.utn.frba.arbiter.siniestros.services.ClasificacionOrquestador;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/classifications")
@RequiredArgsConstructor
@Tag(name = "Classifications", description = "Claim classification with LLM")
public class ClasificacionController {

    private final ClasificacionOrquestador orchestrator;

    @PostMapping
    @Operation(
            summary = "Classify a claim",
            description = """
                    Receives claim data, queries the insured's history
                    and the insurer's business rules, builds a structured prompt
                    and sends it to the LLM (Ollama + Qwen3-VL) to classify the claim.

                    The `policyNumber` and `insuredId` fields are used to query
                    the mocks (in dev/test) or the real insurer API (in prod).
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Classification successful",
                            content = @Content(schema = @Schema(implementation = ClasificacionResponse.class))),
                    @ApiResponse(responseCode = "422", description = "The LLM returned an invalid response"),
                    @ApiResponse(responseCode = "400", description = "Invalid claim data")
            }
    )
    public ResponseEntity<ClasificacionResponse> classify(@Valid @RequestBody DenunciaSiniestro claim) {
        ClasificacionResponse response = orchestrator.classify(claim);
        return ResponseEntity.ok(response);
    }
}
