package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.EmbeddingProperties;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Client for the CLIP embedding sidecar (embedding-service). Unlike the vision LLM,
 * CLIP is deterministic: the same image always produces the same 512-dim vector,
 * which is what pgvector similarity search requires.
 */
@Component
public class ClipClient {

    private static final Logger log = LoggerFactory.getLogger(ClipClient.class);

    private final RestClient restClient;

    public ClipClient(EmbeddingProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.serviceUrl())
                .build();
    }

    @SuppressWarnings("unchecked")
    public float[] embed(String imageBase64) {
        long start = System.currentTimeMillis();

        Map<String, Object> response = restClient.post()
                .uri("/embed")
                .body(Map.of("image_base64", imageBase64))
                .retrieve()
                .body(Map.class);

        List<Number> embedding = (List<Number>) response.get("embedding");
        if (embedding == null || embedding.isEmpty()) {
            throw new InvalidClassificationException("CLIP service returned no embedding");
        }

        float[] result = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            result[i] = embedding.get(i).floatValue();
        }

        log.info("[CLIP] Embedding generated — {} dims in {} ms",
                result.length, System.currentTimeMillis() - start);
        return result;
    }
}
