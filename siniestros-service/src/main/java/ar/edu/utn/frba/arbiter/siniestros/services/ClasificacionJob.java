package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.siniestros.adapters.SiniestroClassifier;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;
import ar.edu.utn.frba.arbiter.siniestros.dto.DenunciaSiniestro;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClasificacionJob {

    private static final Logger log = LoggerFactory.getLogger(ClasificacionJob.class);

    private final SiniestroClassifier classifier;
    private final ClasificacionOrquestador orquestador;
    private final ResultadosClasificacionService resultadosService;

    @Async("clasificacionExecutor")
    public void procesarClasificacion(Long siniestroId, DenunciaSiniestro denuncia) {
        log.info("[ClasificacionJob] Iniciando clasificación async para siniestro {}", siniestroId);

        try {
            long inicio = System.currentTimeMillis();

            ClasificacionResponse respuesta = orquestador.clasificar(denuncia);
            long latenciaMs = System.currentTimeMillis() - inicio;

            resultadosService.guardarResultado(
                    siniestroId,
                    denuncia.polizaNumero(),
                    denuncia.aseguradoDni(),
                    respuesta.clasificacion(),
                    respuesta.confianza(),
                    respuesta.factores(),
                    latenciaMs
            );

            log.info("[ClasificacionJob] Clasificación completada para siniestro {} — {} (latencia: {} ms)",
                    siniestroId, respuesta.clasificacion(), latenciaMs);

        } catch (Exception e) {
            log.error("[ClasificacionJob] Error procesando siniestro {}", siniestroId, e);
        }
    }
}
