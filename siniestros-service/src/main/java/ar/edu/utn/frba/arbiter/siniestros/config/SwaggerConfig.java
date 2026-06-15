package ar.edu.utn.frba.arbiter.siniestros.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Arbiter — Siniestros Service")
                        .description("""
                                Módulo de Análisis y Clasificación de Siniestros.
                                Recibe denuncias, consulta reglas e historial del asegurado,
                                y clasifica usando LLM (Ollama + Qwen3-VL).

                                **Clasificaciones posibles:**
                                - `POTENCIAL_RIESGO` — inconsistencias o indicadores de fraude
                                - `SIN_RIESGO` — denuncia consistente, sin alertas
                                - `FAST_TRACK` — caso simple, puede procesarse de forma expedita

                                **Proyecto Final UTN FRBA · DDSI · K5054 · Grupo 5303**
                                """)
                        .version("v1.0")
                        .contact(new Contact()
                                .name("Grupo 5303")
                                .email("asandoval@frba.utn.edu.ar")))
                .servers(List.of(
                        new Server().url("http://localhost:8082").description("Local dev")
                ));
    }
}
