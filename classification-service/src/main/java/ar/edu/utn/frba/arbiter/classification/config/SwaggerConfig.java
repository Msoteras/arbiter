package ar.edu.utn.frba.arbiter.classification.config;

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
                        .title("Arbiter — Claims Service")
                        .description("""
                                Claims Analysis and Classification Module.
                                Receives claim reports, queries business rules and insured history,
                                and classifies using LLM (Ollama + Qwen3-VL).

                                **Possible classifications:**
                                - `FAST_TRACK` — simple, verifiable case; decided deterministically by business
                                  rules, never by the LLM. Still requires analyst approval.
                                - `FALTA_DOCUMENTACION` — potentially valid but missing documents/evidence
                                - `LLM_RECOMIENDA_APROBAR` — consistent claim, no alerts; LLM recommends approval
                                - `LLM_NO_RECOMIENDA_APROBAR` — inconsistencies or fraud indicators; LLM recommends against approval
                                - `LLM_SOLICITA_REVISION_MANUAL` — LLM is not confident enough to recommend either way

                                All LLM outputs are non-binding recommendations — an analyst always makes the final call.

                                **UTN FRBA Final Project · DDSI · K5054 · Group 5303**
                                """)
                        .version("v1.0")
                        .contact(new Contact()
                                .name("Group 5303")
                                .email("asandoval@frba.utn.edu.ar")))
                .servers(List.of(
                        new Server().url("http://localhost:8082").description("Local dev")
                ));
    }
}
