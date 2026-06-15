package ar.edu.utn.frba.arbiter.siniestros.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
public record PolizaAsegurado(
        String polizaNumero,
        String aseguradoNombre,
        String aseguradoDni,
        String ramo,
        String producto,
        LocalDate vigenciaDesde,
        LocalDate vigenciaHasta,
        boolean alDia,
        BigDecimal sumaAsegurada,
        BigDecimal franquicia,
        List<CoberturaPoliza> coberturas,
        List<String> clausulasAplicables
) {

    @Builder
    public record CoberturaPoliza(
            String codigo,
            String descripcion,
            BigDecimal sumaAsegurada,
            BigDecimal franquicia
    ) {}
}
