package ar.edu.utn.frba.arbiter.siniestros.dto;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;

import java.util.List;

public record ClasificacionResponse(
        Clasificacion clasificacion,
        List<String> factores,
        double confianza
) {}
