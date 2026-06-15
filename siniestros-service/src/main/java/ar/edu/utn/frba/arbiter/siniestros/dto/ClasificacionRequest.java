package ar.edu.utn.frba.arbiter.siniestros.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record ClasificacionRequest(
        String ramo,
        String producto,
        String hechoGenerador,
        String bienAsegurado,
        String descripcionLibre,
        List<String> adjuntosOCR,
        String imagenBase64,
        String reglasAseguradora,
        String historialAsegurado
) {}
