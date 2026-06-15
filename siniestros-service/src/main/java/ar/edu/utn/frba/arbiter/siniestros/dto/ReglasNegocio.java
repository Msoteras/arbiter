package ar.edu.utn.frba.arbiter.siniestros.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record ReglasNegocio(
        String ramoId,
        String hechoGeneradorId,
        List<String> reglas,
        List<String> exclusiones,
        List<String> criteriosFastTrack
) {}
