package ar.edu.utn.frba.arbiter.siniestros.dto;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import lombok.Builder;

import java.util.List;

@Builder
public record ClasificacionResponse(
        Clasificacion clasificacion,
        List<String> factores,
        double confianza
) {}
