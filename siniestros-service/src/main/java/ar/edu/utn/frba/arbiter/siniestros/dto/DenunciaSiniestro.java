package ar.edu.utn.frba.arbiter.siniestros.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record DenunciaSiniestro(
        @NotBlank String ramo,
        @NotBlank String producto,
        @NotBlank String hechoGenerador,
        @NotBlank String bienAsegurado,
        @NotBlank String aseguradoDni,
        @NotBlank String polizaNumero,
        @NotBlank String descripcionLibre,
        @NotNull LocalDateTime fechaHecho,
        @NotBlank String lugarHecho,
        List<String> adjuntosOCR,
        String imagenBase64
) {}
