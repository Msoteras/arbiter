package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.RegisterFraudRecordRequest;
import ar.edu.utn.frba.arbiter.cases.services.FraudRecordService;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordResponse;
import io.swagger.v3.oas.annotations.Operation;
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

import java.util.List;

/**
 * Antecedente de fraude del asegurado, registrado desde el expediente que lo originó.
 *
 * <p>Aparte de {@code /decision} y de {@code /expert-assessment/report}, por lo mismo que la
 * derivación: el peritaje verifica un hecho de <b>este</b> siniestro, y decidir que ese hecho
 * acompañe a la persona en el próximo es otro acto, del analista. No se expone al ASEGURADO.
 */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/fraud-record")
@RequiredArgsConstructor
@Tag(name = "Antecedente de fraude", description = "Registro del antecedente de fraude del asegurado")
public class FraudRecordController {

    private final FraudRecordService fraudRecordService;

    @PostMapping
    @PreAuthorize("hasRole('ANALISTA_SINIESTROS')")
    @Operation(summary = "Registrar el antecedente de fraude del asegurado",
            description = """
                    Marca el expediente como fraude determinado y deja el antecedente asociado al
                    asegurado, para que pese en sus denuncias siguientes.

                    Dos orígenes, con peso distinto:
                    - `EXPERT_BACKED`: exige que el expediente tenga un peritaje con
                      `FRAUD_CONFIRMED`. Puntúa en el scoring y, si la aseguradora lo configuró
                      así, veta el Fast Track de la próxima denuncia.
                    - `ANALYST_DECLARED`: la determinación del analista sin peritaje. Queda visible
                      para quien revise la próxima denuncia y **no** entra al motor — una sospecha
                      que mueve el score realimenta el score de mañana y no deja salida.

                    Solo desde PENDING_ANALYST_REVIEW o REJECTED, y una sola vez por expediente
                    (409). Aprobar el siniestro y registrarlo como fraude se contradicen, así que
                    desde APPROVED no se puede.
                    """)
    public ResponseEntity<FraudRecordResponse> register(
            @PathVariable Long caseId,
            @RequestBody @Valid RegisterFraudRecordRequest request
    ) {
        return ResponseEntity.ok(fraudRecordService.register(caseId, request));
    }

    @GetMapping("/insured")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Antecedentes del asegurado de este expediente",
            description = """
                    Lo que el analista necesita ver al lado del peritaje: si la persona que denuncia
                    ya tuvo un fraude determinado antes.

                    Incluye los vencidos (`inForce=false`) y los declarados sin peritaje
                    (`scores=false`): que el antecedente exista y que cuente son dos cosas
                    distintas, y la pantalla tiene que poder decir cuál es cuál.
                    """)
    public ResponseEntity<List<FraudRecordResponse>> insuredRecords(@PathVariable Long caseId) {
        return ResponseEntity.ok(fraudRecordService.insuredRecords(caseId));
    }
}
