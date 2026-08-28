package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.DerivationOptionsResponse;
import ar.edu.utn.frba.arbiter.cases.dto.DeriveToExpertRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ExpertAssessmentResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.ExpertAssessmentNotFoundException;
import ar.edu.utn.frba.arbiter.cases.services.ExpertAssessmentService;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Derivación a peritaje. Separado de {@code POST /cases/{id}/decision} a propósito: derivar no
 * es un veredicto, es suspender el expediente para conseguir evidencia. El endpoint de decisión
 * escribe en el registro auditable inmutable, y mezclar las dos cosas ensuciaría justo el
 * registro que la Disposición SSN 2/2023 pide mantener limpio.
 */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/expert-assessment")
@RequiredArgsConstructor
@Tag(name = "Expert assessment", description = "Derivación de un expediente a peritaje externo")
public class ExpertAssessmentController {

    private final ExpertAssessmentService expertAssessmentService;

    @GetMapping("/options")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Si este expediente se puede derivar, y a quién",
            description = """
                    `eligible` combina la regla de la aseguradora (monto reclamado contra el mínimo
                    configurado en rules-service) con que haya peritos disponibles: una política que
                    habilita sobre un catálogo vacío igual deja al analista sin a quién derivar.

                    `firms` son los peritos activos del ramo del siniestro más los generalistas. El
                    analista elige de la lista y no escribe una dirección a mano, así queda registro
                    de con quién trabaja la aseguradora.

                    Devuelve los dos montos y no solo el veredicto, para que la pantalla pueda
                    explicar por qué no se puede en vez de mostrar un botón apagado sin motivo.
                    """)
    public ResponseEntity<DerivationOptionsResponse> options(@PathVariable Long caseId) {
        return ResponseEntity.ok(expertAssessmentService.options(caseId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "El peritaje del expediente",
            description = """
                    404 si el expediente nunca se derivó. Mientras no vuelva el informe,
                    `reportReceivedAt` y `verdict` son null.

                    No se expone al ASEGURADO: para él el expediente sigue 'En análisis', y contarle
                    que se derivó filtraría la sospecha que motivó la derivación.
                    """)
    public ResponseEntity<ExpertAssessmentResponse> get(@PathVariable Long caseId) {
        return ResponseEntity.ok(expertAssessmentService.find(caseId)
                .orElseThrow(() -> new ExpertAssessmentNotFoundException(caseId)));
    }

    // Solo el analista: la derivación queda atribuida (ExpertAssessment.derivedBy es un
    // ClaimsAnalyst) y el servicio ya devolvía 403 al referente por no tener perfil.
    @PostMapping
    @PreAuthorize("hasRole('ANALISTA_SINIESTROS')")
    @Operation(summary = "Derivar el expediente a un perito",
            description = """
                    Pasa el expediente a PENDING_EXPERT_REPORT y le manda al perito, por mail, los
                    datos del siniestro a verificar (sin la clasificación ni el score: se le pide
                    verificar hechos, no confirmar una sospecha).

                    Solo desde PENDING_ANALYST_REVIEW (otro estado → 409), y una sola vez por
                    expediente. No resuelve nada: el caso vuelve al analista, que sigue siendo
                    quien decide (decisión de arquitectura #5).
                    """)
    public ResponseEntity<ExpertAssessmentResponse> derive(
            @PathVariable Long caseId,
            @RequestBody @Valid DeriveToExpertRequest request
    ) {
        return ResponseEntity.accepted().body(expertAssessmentService.derive(caseId, request));
    }

    // También el referente: transcribe el veredicto del perito y devuelve el caso a la cola del
    // analista, que sigue siendo quien decide. No atribuye nada a quien lo sube.
    @PostMapping(value = "/report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Cargar el informe del perito",
            description = """
                    El analista sube el informe que recibió y registra su conclusión. El expediente
                    vuelve a PENDING_ANALYST_REVIEW con el veredicto al lado de la clasificación.

                    **No se reclasifica.** El informe es evidencia de una persona que inspeccionó el
                    caso; volver a pasarlo por el modelo solo lograría que lo repita o que lo
                    contradiga. 409 si el informe ya había llegado.

                    `verdict` y `note` viajan como campos del multipart, no en la query string: la
                    nota es texto libre sobre un siniestro y en la URL terminaría en los logs del
                    reverse proxy.
                    """)
    public ResponseEntity<ExpertAssessmentResponse> receiveReport(
            @PathVariable Long caseId,
            @RequestParam ExpertVerdict verdict,
            @RequestParam(required = false) String note,
            @RequestPart("report") MultipartFile report
    ) {
        return ResponseEntity.ok(expertAssessmentService.receiveReport(caseId, verdict, note, report));
    }
}
