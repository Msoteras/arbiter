package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.services.ClassificationOutcomeService;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationFinished;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
@Tag(name = "Cases", description = "Case lifecycle management")
public class ClassificationFinishedController {

    private final ClassificationOutcomeService classificationOutcomeService;

    @PostMapping("/{caseId}/classification-finished")
    @PreAuthorize("authentication.authorities.isEmpty()")
    @Operation(summary = "Aviso de clasificación terminada",
            description = """
                    Lo llama classification-service, solo con token de servicio, cuando termina la
                    clasificación de un expediente. Si salió bien, el expediente pasa al estado que
                    corresponde al resultado; si falló, a "Clasificación fallida". Si el expediente ya
                    no está pendiente de clasificación, no cambia nada.
                    """)
    public ResponseEntity<Void> classificationFinished(@PathVariable Long caseId,
                                                       @RequestBody @Valid ClassificationFinished notice) {
        classificationOutcomeService.onClassificationFinished(caseId, notice.outcome());
        return ResponseEntity.noContent().build();
    }
}
