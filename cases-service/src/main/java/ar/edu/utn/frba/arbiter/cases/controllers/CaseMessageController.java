package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageThreadResponse;
import ar.edu.utn.frba.arbiter.cases.services.CaseMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The conversation on a case. Reading is open to anyone who can read the case — the referente
 * included, in the same read-only way they see the inbox; writing is only for the two parties.
 *
 * <p>{@code insurer} is the slug that disambiguates {@code caseId}, which repeats across schemas.
 */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/messages")
@RequiredArgsConstructor
@Tag(name = "Case messages", description = "Conversación entre el asegurado y el analista")
public class CaseMessageController {

    private final CaseMessageService messageService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "El hilo del expediente, del más viejo al más nuevo")
    public ResponseEntity<CaseMessageThreadResponse> thread(
            @PathVariable Long caseId,
            @RequestParam(required = false) String insurer
    ) {
        return ResponseEntity.ok(messageService.thread(caseId, insurer));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ASEGURADO', 'ANALISTA_SINIESTROS')")
    @Operation(summary = "Escribir en el hilo del expediente")
    public ResponseEntity<CaseMessageResponse> post(
            @PathVariable Long caseId,
            @RequestParam(required = false) String insurer,
            @Valid @RequestBody CaseMessageRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messageService.post(caseId, insurer, request.body()));
    }

    @PostMapping("/read")
    @PreAuthorize("hasAnyRole('ASEGURADO', 'ANALISTA_SINIESTROS')")
    @Operation(summary = "Marcar como leídos los mensajes de la otra parte")
    public ResponseEntity<Void> markRead(
            @PathVariable Long caseId,
            @RequestParam(required = false) String insurer
    ) {
        messageService.markRead(caseId, insurer);
        return ResponseEntity.noContent().build();
    }
}
