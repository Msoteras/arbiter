package ar.edu.utn.frba.arbiter.siniestros.adapters.mock;

import ar.edu.utn.frba.arbiter.siniestros.adapters.AseguradoraAdapter;
import ar.edu.utn.frba.arbiter.siniestros.dto.HistorialAsegurado;
import ar.edu.utn.frba.arbiter.siniestros.dto.HistorialAsegurado.SiniestroHistorico;
import ar.edu.utn.frba.arbiter.siniestros.dto.PolizaAsegurado;
import ar.edu.utn.frba.arbiter.siniestros.dto.PolizaAsegurado.CoberturaPoliza;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
@Profile({"dev", "test", "default"})
public class MockAseguradoraAdapter implements AseguradoraAdapter {

    private static final Map<String, PolizaAsegurado> POLIZAS = Map.of(
            "POL-CEL-2024-001", PolizaAsegurado.builder()
                    .polizaNumero("POL-CEL-2024-001")
                    .aseguradoNombre("Laura Fernández")
                    .aseguradoDni("40.123.456")
                    .ramo("Celulares")
                    .producto("Celular Protegido Básico")
                    .vigenciaDesde(LocalDate.of(2024, 3, 1))
                    .vigenciaHasta(LocalDate.of(2027, 3, 1))
                    .alDia(true)
                    .sumaAsegurada(new BigDecimal("400000"))
                    .franquicia(new BigDecimal("50000"))
                    .coberturas(List.of(
                            CoberturaPoliza.builder()
                                    .codigo("COB-ROB-01")
                                    .descripcion("Robo y/o hurto del equipo")
                                    .sumaAsegurada(new BigDecimal("400000"))
                                    .franquicia(new BigDecimal("50000"))
                                    .build(),
                            CoberturaPoliza.builder()
                                    .codigo("COB-ROT-01")
                                    .descripcion("Rotura accidental de pantalla")
                                    .sumaAsegurada(new BigDecimal("200000"))
                                    .franquicia(new BigDecimal("30000"))
                                    .build()
                    ))
                    .clausulasAplicables(List.of("100 — Exclusión robo en domicilio", "105 — Franquicia fija"))
                    .build(),

            "POL-CEL-2025-099", PolizaAsegurado.builder()
                    .polizaNumero("POL-CEL-2025-099")
                    .aseguradoNombre("Marcelo Gómez")
                    .aseguradoDni("30.555.777")
                    .ramo("Celulares")
                    .producto("Celular Protegido Premium")
                    .vigenciaDesde(LocalDate.of(2025, 6, 1))
                    .vigenciaHasta(LocalDate.of(2026, 6, 1))
                    .alDia(true)
                    .sumaAsegurada(new BigDecimal("1200000"))
                    .franquicia(new BigDecimal("80000"))
                    .coberturas(List.of(
                            CoberturaPoliza.builder()
                                    .codigo("COB-ROB-02")
                                    .descripcion("Robo y/o hurto — cobertura premium")
                                    .sumaAsegurada(new BigDecimal("1200000"))
                                    .franquicia(new BigDecimal("80000"))
                                    .build(),
                            CoberturaPoliza.builder()
                                    .codigo("COB-ROT-02")
                                    .descripcion("Rotura accidental — cobertura total")
                                    .sumaAsegurada(new BigDecimal("1200000"))
                                    .franquicia(new BigDecimal("60000"))
                                    .build()
                    ))
                    .clausulasAplicables(List.of("100 — Exclusión robo en domicilio", "102 — Reposición a nuevo", "344 — Cobertura mundial"))
                    .build(),

            "POL-CEL-2026-042", PolizaAsegurado.builder()
                    .polizaNumero("POL-CEL-2026-042")
                    .aseguradoNombre("Sofía Martínez")
                    .aseguradoDni("42.987.654")
                    .ramo("Celulares")
                    .producto("Celular Protegido Premium")
                    .vigenciaDesde(LocalDate.of(2026, 1, 10))
                    .vigenciaHasta(LocalDate.of(2027, 1, 10))
                    .alDia(true)
                    .sumaAsegurada(new BigDecimal("1300000"))
                    .franquicia(new BigDecimal("57000"))
                    .coberturas(List.of(
                            CoberturaPoliza.builder()
                                    .codigo("COB-ROB-02")
                                    .descripcion("Robo y/o hurto — cobertura premium")
                                    .sumaAsegurada(new BigDecimal("1300000"))
                                    .franquicia(new BigDecimal("80000"))
                                    .build(),
                            CoberturaPoliza.builder()
                                    .codigo("COB-ROT-03")
                                    .descripcion("Rotura accidental — sin límite de eventos")
                                    .sumaAsegurada(new BigDecimal("1300000"))
                                    .franquicia(new BigDecimal("57000"))
                                    .build()
                    ))
                    .clausulasAplicables(List.of("100 — Exclusión robo en domicilio", "102 — Reposición a nuevo"))
                    .build()
    );

    private static final Map<String, HistorialAsegurado> HISTORIALES = Map.of(
            "40.123.456", HistorialAsegurado.builder()
                    .aseguradoDni("40.123.456")
                    .cantidadSiniestrosPrevios(0)
                    .montoTotalReclamado(BigDecimal.ZERO)
                    .clienteDesde(LocalDate.of(2024, 3, 1))
                    .siniestros(List.of())
                    .build(),

            "30.555.777", HistorialAsegurado.builder()
                    .aseguradoDni("30.555.777")
                    .cantidadSiniestrosPrevios(3)
                    .montoTotalReclamado(new BigDecimal("2440000"))
                    .clienteDesde(LocalDate.of(2025, 6, 1))
                    .siniestros(List.of(
                            SiniestroHistorico.builder()
                                    .siniestroId("2025-4401")
                                    .fecha(LocalDate.of(2025, 11, 15))
                                    .ramo("Celulares")
                                    .hechoGenerador("Robo en vía pública")
                                    .bienAfectado("Samsung Galaxy S24 Ultra - IMEI 353000000000055")
                                    .estado("Aprobado")
                                    .montoReclamado(new BigDecimal("450000"))
                                    .montoLiquidado(new BigDecimal("400000"))
                                    .observaciones("Denuncia consistente. Sin señales de alerta.")
                                    .build(),
                            SiniestroHistorico.builder()
                                    .siniestroId("2026-1892")
                                    .fecha(LocalDate.of(2026, 2, 3))
                                    .ramo("Celulares")
                                    .hechoGenerador("Hurto")
                                    .bienAfectado("iPhone 15 Pro - IMEI 353000000000077")
                                    .estado("Aprobado")
                                    .montoReclamado(new BigDecimal("890000"))
                                    .montoLiquidado(new BigDecimal("810000"))
                                    .observaciones("Demora en denuncia policial (72 hs). Aprobado por antecedente limpio previo.")
                                    .build(),
                            SiniestroHistorico.builder()
                                    .siniestroId("2026-3310")
                                    .fecha(LocalDate.of(2026, 4, 28))
                                    .ramo("Celulares")
                                    .hechoGenerador("Robo en vía pública")
                                    .bienAfectado("iPhone 16 Pro - IMEI 353000000000088")
                                    .estado("En investigación")
                                    .montoReclamado(new BigDecimal("1100000"))
                                    .montoLiquidado(null)
                                    .observaciones("Tercer siniestro en 6 meses. Derivado a investigación por frecuencia.")
                                    .build()
                    ))
                    .build(),

            "42.987.654", HistorialAsegurado.builder()
                    .aseguradoDni("42.987.654")
                    .cantidadSiniestrosPrevios(0)
                    .montoTotalReclamado(BigDecimal.ZERO)
                    .clienteDesde(LocalDate.of(2026, 1, 10))
                    .siniestros(List.of())
                    .build()
    );

    @Override
    public PolizaAsegurado obtenerPoliza(String polizaNumero) {
        var poliza = POLIZAS.get(polizaNumero);
        if (poliza == null) {
            throw new IllegalArgumentException("Póliza mock no encontrada: " + polizaNumero);
        }
        return poliza;
    }

    @Override
    public HistorialAsegurado obtenerHistorial(String aseguradoDni) {
        return HISTORIALES.getOrDefault(aseguradoDni,
                HistorialAsegurado.builder()
                        .aseguradoDni(aseguradoDni)
                        .cantidadSiniestrosPrevios(0)
                        .montoTotalReclamado(BigDecimal.ZERO)
                        .clienteDesde(LocalDate.now())
                        .siniestros(List.of())
                        .build()
        );
    }
}
