package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.siniestros.adapters.SiniestroClassifier;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;
import ar.edu.utn.frba.arbiter.siniestros.dto.DenunciaSiniestro;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@Service
@RequiredArgsConstructor
public class ClasificacionJob {

    private static final Logger log = LoggerFactory.getLogger(ClasificacionJob.class);

    private final SiniestroClassifier classifier;
    private final ClasificacionOrquestador orquestador;
    private final ResultadosClasificacionService resultadosService;

    @Async("clasificacionExecutor")
    @Retryable(
            retryFor = {HttpServerErrorException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public void procesarClasificacion(Long siniestroId, DenunciaSiniestro denuncia) {
        log.info("[ClasificacionJob] ▶ Iniciando clasificación async — siniestroId={} póliza='{}' dni='{}'",
                siniestroId, denuncia.polizaNumero(), denuncia.aseguradoDni());

        try {
            long inicio = System.currentTimeMillis();

            log.debug("[ClasificacionJob] → Orquestando: consultando póliza, historial, reglas...");
            ClasificacionResponse respuesta = orquestador.clasificar(denuncia);
            long latenciaMs = System.currentTimeMillis() - inicio;

            log.info("[ClasificacionJob] ✓ Clasificación obtenida — siniestroId={} clasificacion={} confianza={:.2f} latencia={}ms",
                    siniestroId, respuesta.clasificacion(), respuesta.confianza(), latenciaMs);

            log.debug("[ClasificacionJob] → Guardando resultado en archivo de resultados...");
            resultadosService.guardarResultado(
                    siniestroId,
                    denuncia.polizaNumero(),
                    denuncia.aseguradoDni(),
                    respuesta.clasificacion(),
                    respuesta.confianza(),
                    respuesta.factores(),
                    latenciaMs
            );

            log.info("[ClasificacionJob] ✓ COMPLETADO — siniestroId={} {} | Confianza: {:.2f} | Latencia: {}ms",
                    siniestroId, respuesta.clasificacion(), respuesta.confianza(), latenciaMs);
            log.debug("[ClasificacionJob]   Factores: {}", respuesta.factores());

        } catch (Exception e) {
            log.error("[ClasificacionJob] ✗ Error procesando siniestro {} tras reintentos — {}",
                    siniestroId, e.getMessage(), e);
            throw new RuntimeException("Falló clasificación de siniestro " + siniestroId + " tras reintentos", e);
        }
    }
}
