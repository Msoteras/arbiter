package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import ar.edu.utn.frba.arbiter.siniestros.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.siniestros.dto.ClasificacionResponse;
import ar.edu.utn.frba.arbiter.siniestros.dto.DenunciaSiniestro;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test de integración contra Ollama real (OPCIONAL).
 * Requiere Ollama corriendo con qwen3-vl en la URL configurada.
 * Se salta automáticamente si Ollama no está disponible.
 *
 * Correr: mvn -pl siniestros-service test -Dgroups=ollama -Dtest=ClasificacionOllamaIntegrationTest
 * O simplemente: mvn -pl siniestros-service test (se salta si Ollama no está disponible)
 */
@Tag("ollama")
@SpringBootTest
@ActiveProfiles("test")
class ClasificacionOllamaIntegrationTest {

    @Autowired
    private OllamaProperties ollamaProperties;

    @Autowired
    private ClasificacionOrquestador orquestador;

    @BeforeEach
    void verificarOllamaDisponible() {
        String url = ollamaProperties.baseUrl() + "/api/tags";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.connect();
            assumeTrue(conn.getResponseCode() == 200, "Ollama no disponible en " + url);
        } catch (IOException e) {
            assumeTrue(false, "Ollama no disponible — " + e.getMessage());
        }
    }

    @Test
    void denunciaReincidente_validarRespuestaOllama() {
        DenunciaSiniestro denuncia = DenunciaSiniestro.builder()
                .ramo("Celulares")
                .producto("Celular Protegido Premium")
                .hechoGenerador("Robo en vía pública")
                .bienAsegurado("iPhone 16 Pro Max 256GB - IMEI 353000000000099")
                .aseguradoDni("30.555.777")
                .polizaNumero("POL-CEL-2025-099")
                .descripcionLibre(
                        "Me robaron el celular el martes a la noche, estaba en la calle creo que por " +
                        "Palermo o tal vez Belgrano, no me acuerdo bien la dirección exacta. Eran como " +
                        "las 11 o 12 de la noche. Vino un tipo y me lo sacó de la mano. No vi bien " +
                        "porque estaba oscuro. Hice la denuncia al día siguiente."
                )
                .fechaHecho(LocalDateTime.of(2026, 6, 10, 23, 0))
                .lugarHecho("Palermo, CABA (ubicación imprecisa)")
                .adjuntosOCR(List.of(
                        "DENUNCIA POLICIAL Nro 2026/78901 - Comisaría 14va CABA\n" +
                        "Fecha: 12/06/2026 09:15 hs\n" +
                        "Denunciante: Marcelo Gómez DNI 30.555.777\n" +
                        "Hecho: Robo de teléfono celular"
                ))
                .build();

        ClasificacionResponse respuesta = orquestador.clasificar(denuncia);

        System.out.println("\n=== Respuesta Ollama real ===");
        System.out.println("Clasificación: " + respuesta.clasificacion());
        System.out.println("Confianza: " + respuesta.confianza());
        System.out.println("Factores: " + respuesta.factores());

        assertThat(respuesta.clasificacion()).isIn(
                Clasificacion.POTENCIAL_RIESGO,
                Clasificacion.FALTA_DOCUMENTACION,
                Clasificacion.FAST_TRACK
        );
        assertThat(respuesta.factores()).isNotEmpty();
        assertThat(respuesta.confianza()).isBetween(0.0, 1.0);
    }
}
