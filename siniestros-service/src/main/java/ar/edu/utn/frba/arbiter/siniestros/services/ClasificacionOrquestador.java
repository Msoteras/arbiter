package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.siniestros.adapters.AseguradoraAdapter;
import ar.edu.utn.frba.arbiter.siniestros.adapters.ReglasAdapter;
import ar.edu.utn.frba.arbiter.siniestros.adapters.SiniestroClassifier;
import ar.edu.utn.frba.arbiter.siniestros.dto.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClasificacionOrquestador {

    private static final Logger log = LoggerFactory.getLogger(ClasificacionOrquestador.class);

    private final SiniestroClassifier classifier;
    private final ReglasAdapter reglasAdapter;
    private final AseguradoraAdapter aseguradoraAdapter;

    public ClasificacionResponse clasificar(DenunciaSiniestro denuncia) {
        log.info("[Orquestador] Iniciando clasificación — poliza='{}' dni='{}' ramo='{}' hechoGenerador='{}'",
                denuncia.polizaNumero(), denuncia.aseguradoDni(), denuncia.ramo(), denuncia.hechoGenerador());

        log.debug("[Orquestador] Consultando póliza '{}'...", denuncia.polizaNumero());
        PolizaAsegurado poliza = aseguradoraAdapter.obtenerPoliza(denuncia.polizaNumero());
        log.info("[Orquestador] Póliza OK — asegurado='{}' alDia={} sumaAsegurada={}",
                poliza.aseguradoNombre(), poliza.alDia(), poliza.sumaAsegurada());

        log.debug("[Orquestador] Consultando historial DNI '{}'...", denuncia.aseguradoDni());
        HistorialAsegurado historial = aseguradoraAdapter.obtenerHistorial(denuncia.aseguradoDni());
        log.info("[Orquestador] Historial OK — siniestros_previos={} monto_total_reclamado={}",
                historial.cantidadSiniestrosPrevios(), historial.montoTotalReclamado());

        log.debug("[Orquestador] Consultando reglas — ramo='{}' hechoGenerador='{}'...",
                denuncia.ramo(), denuncia.hechoGenerador());
        ReglasNegocio reglas = reglasAdapter.obtenerReglas(denuncia.ramo(), denuncia.hechoGenerador());
        log.info("[Orquestador] Reglas OK — {} reglas, {} exclusiones, {} criterios fast-track",
                reglas.reglas().size(), reglas.exclusiones().size(), reglas.criteriosFastTrack().size());

        log.info("[Orquestador] Armando prompt y enviando a LLM...");
        ClasificacionRequest request = armarRequest(denuncia, poliza, historial, reglas);
        ClasificacionResponse respuesta = classifier.clasificar(request);

        log.info("[Orquestador] Clasificación finalizada — resultado={} confianza={}",
                respuesta.clasificacion(), respuesta.confianza());
        return respuesta;
    }

    private ClasificacionRequest armarRequest(
            DenunciaSiniestro denuncia,
            PolizaAsegurado poliza,
            HistorialAsegurado historial,
            ReglasNegocio reglas
    ) {
        return ClasificacionRequest.builder()
                .ramo(denuncia.ramo())
                .producto(denuncia.producto())
                .hechoGenerador(denuncia.hechoGenerador())
                .bienAsegurado(denuncia.bienAsegurado())
                .descripcionLibre(denuncia.descripcionLibre())
                .adjuntosOCR(denuncia.adjuntosOCR())
                .imagenBase64(denuncia.imagenBase64())
                .reglasAseguradora(formatearReglas(reglas, poliza))
                .historialAsegurado(formatearHistorial(historial))
                .build();
    }

    private String formatearReglas(ReglasNegocio reglas, PolizaAsegurado poliza) {
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

    private String formatearHistorial(HistorialAsegurado historial) {
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
