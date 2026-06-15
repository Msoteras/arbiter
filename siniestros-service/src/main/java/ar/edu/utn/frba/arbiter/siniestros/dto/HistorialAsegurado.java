package ar.edu.utn.frba.arbiter.siniestros.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
public record HistorialAsegurado(
        String aseguradoDni,
        int cantidadSiniestrosPrevios,
        BigDecimal montoTotalReclamado,
        LocalDate clienteDesde,
        List<SiniestroHistorico> siniestros
) {

    @Builder
    public record SiniestroHistorico(
            String siniestroId,
            LocalDate fecha,
            String ramo,
            String hechoGenerador,
            String bienAfectado,
            String estado,
            BigDecimal montoReclamado,
            BigDecimal montoLiquidado,
            String observaciones
    ) {}
}
