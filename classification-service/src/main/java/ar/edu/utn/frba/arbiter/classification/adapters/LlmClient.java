package ar.edu.utn.frba.arbiter.classification.adapters;

import java.util.List;
import java.util.Map;

/** Transport to a language model; the implementation is chosen by {@code arbiter.llm.provider} (default {@code ollama}). */
public interface LlmClient {

    /**
     * @param images base64-encoded images, or empty for text-only
     * @param format JSON Schema forcing structured output, or null for free text
     * @param think  whether the model may reason first; implementations may ignore it
     */
    String chat(String prompt, List<String> images, Map<String, Object> format, boolean think);

    /** Persisted in {@code llm_analysis.model} for the audit trail. */
    String model();

    /** Used to warn before overflowing it: Ollama silently drops the excess. */
    int contextWindow();
}
