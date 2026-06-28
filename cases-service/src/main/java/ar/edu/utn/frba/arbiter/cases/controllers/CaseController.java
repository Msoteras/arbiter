package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.services.CaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
@Tag(name = "Cases", description = "Case lifecycle management")
public class CaseController {

    private final CaseService caseService;

    @PostMapping
    @Operation(summary = "Create a case",
            description = "Registers a case using the same request structure as the claim flow and triggers an analysis with classification-service.")
    public ResponseEntity<CaseResponse> createCase(
            @RequestBody @Valid CaseRequest request
    ) {
        CaseResponse response = caseService.createCase(request);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{caseId}")
    @Operation(summary = "Get case by id",
            description = "Returns the stored case and its analysis result.")
    public ResponseEntity<CaseResponse> getCase(@PathVariable Long caseId) {
        CaseResponse response = caseService.getCase(caseId);
        return ResponseEntity.ok(response);
    }
}
