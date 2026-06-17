package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.siniestros.dto.HistorialAsegurado;
import ar.edu.utn.frba.arbiter.siniestros.dto.PolizaAsegurado;
import ar.edu.utn.frba.arbiter.siniestros.dto.ReglasNegocio;

import java.util.Objects;

public class PromptBuilder {

    private ReglasNegocio reglas;
    private PolizaAsegurado poliza;
    private HistorialAsegurado historial;

    public PromptBuilder conReglas(ReglasNegocio reglas) {
        this.reglas = reglas;
        return this;
    }

    public PromptBuilder conPoliza(PolizaAsegurado poliza) {
        this.poliza = poliza;
        return this;
    }

    public PromptBuilder conHistorial(HistorialAsegurado historial) {
        this.historial = historial;
        return this;
    }

    public String construirReglasYPoliza() {
        Objects.requireNonNull(reglas, "Reglas no puede ser null");
        Objects.requireNonNull(poliza, "Póliza no puede ser null");

        var sb = new StringBuilder();

        sb.append("REGLAS DE LA ASEGURADORA (ramo: %s, hecho generador: %s):\n"
                .formatted(reglas.ramoId(), reglas.hechoGeneradorId()));
        reglas.reglas().forEach(r -> sb.append("- ").append(r).append("\n"));

        if (!reglas.exclusiones().isEmpty()) {
            sb.append("\nEXCLUSIONES DE COBERTURA:\n");
            reglas.exclusiones().forEach(e -> sb.append("- ").append(e).append("\n"));
        }

        if (!reglas.criteriosFastTrack().isEmpty()) {
            sb.append("\nCRITERIOS FAST TRACK (si se cumplen todos, el caso es expedito):\n");
            reglas.criteriosFastTrack().forEach(c -> sb.append("- ").append(c).append("\n"));
        }

        sb.append("\nDATOS DE LA PÓLIZA:\n");
        sb.append("- Número: %s\n".formatted(poliza.polizaNumero()));
        sb.append("- Estado de pago: %s\n".formatted(poliza.alDia() ? "Al día" : "CON MORA"));
        sb.append("- Vigencia: %s a %s\n".formatted(poliza.vigenciaDesde(), poliza.vigenciaHasta()));
        sb.append("- Suma asegurada: $%s\n".formatted(poliza.sumaAsegurada()));
        sb.append("- Franquicia: $%s\n".formatted(poliza.franquicia()));

        if (!poliza.clausulasAplicables().isEmpty()) {
            sb.append("- Cláusulas: %s\n".formatted(String.join(", ", poliza.clausulasAplicables())));
        }

        return sb.toString();
    }

    public String construirHistorial() {
        Objects.requireNonNull(historial, "Historial no puede ser null");

        var sb = new StringBuilder();

        sb.append("HISTORIAL DEL ASEGURADO (DNI: %s)\n".formatted(historial.aseguradoDni()));
        sb.append("- Cliente desde: %s\n".formatted(historial.clienteDesde()));
        sb.append("- Siniestros previos: %d\n".formatted(historial.cantidadSiniestrosPrevios()));
        sb.append("- Monto total reclamado histórico: $%s\n".formatted(historial.montoTotalReclamado()));

        if (historial.siniestros().isEmpty()) {
            sb.append("\nSin siniestros previos registrados.");
        } else {
            sb.append("\nDETALLE DE SINIESTROS PREVIOS:\n");
            for (var s : historial.siniestros()) {
                sb.append("\n  Siniestro %s — %s\n".formatted(s.siniestroId(), s.fecha()));
                sb.append("    Ramo: %s | Hecho: %s\n".formatted(s.ramo(), s.hechoGenerador()));
                sb.append("    Bien: %s\n".formatted(s.bienAfectado()));
                sb.append("    Estado: %s | Reclamado: $%s | Liquidado: $%s\n"
                        .formatted(s.estado(), s.montoReclamado(),
                                s.montoLiquidado() != null ? s.montoLiquidado() : "—"));
                if (s.observaciones() != null && !s.observaciones().isBlank()) {
                    sb.append("    Obs: %s\n".formatted(s.observaciones()));
                }
            }
        }

        return sb.toString();
    }
}
