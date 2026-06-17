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
        var promptBuilder = new PromptBuilder()
                .conReglas(reglas)
                .conPoliza(poliza)
                .conHistorial(historial);

        return ClasificacionRequest.builder()
                .ramo(denuncia.ramo())
                .producto(denuncia.producto())
                .hechoGenerador(denuncia.hechoGenerador())
                .bienAsegurado(denuncia.bienAsegurado())
                .descripcionLibre(denuncia.descripcionLibre())
                .adjuntosOCR(denuncia.adjuntosOCR())
                .imagenBase64(denuncia.imagenBase64())
                .reglasAseguradora(promptBuilder.construirReglasYPoliza())
                .historialAsegurado(promptBuilder.construirHistorial())
                .build();
    }
}
