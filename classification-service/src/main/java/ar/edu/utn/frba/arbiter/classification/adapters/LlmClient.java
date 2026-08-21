package ar.edu.utn.frba.arbiter.classification.adapters;

import java.util.List;
import java.util.Map;

/**
 * The one call this module makes to a language model, whoever serves it.
 *
 * <p>Extracted from {@link OllamaClient} when Gemini was added: the two adapters above
 * ({@link ClaimClassifierImpl}, {@link DocumentAnalyzerImpl}) build prompts and parse results, and
 * neither has a reason to know whether the tokens came from a model running next door or from an
 * API. Only the implementations speak HTTP.
 *
 * <p>Which one is wired comes from {@code arbiter.llm.provider}. The default stays {@code ollama}:
 * it is the deployment the architecture document describes, and nobody's environment should start
 * calling a paid API because they pulled a branch.
 */
public interface LlmClient {

    /**
     * One logical call to the model. Returns the raw text of the answer — with {@code format} set,
     * that text is the JSON the schema forced; the caller parses it.
     *
     * @param images base64-encoded images for the vision model, or empty for text-only.
     * @param format JSON Schema forcing structured output, or null for free text.
     * @param think  whether to let the model reason before answering. Honoured where it means
     *               something and ignored where it doesn't — see each implementation.
     */
    String chat(String prompt, List<String> images, Map<String, Object> format, boolean think);

    /**
     * Which model answered. Goes to {@code llm_analysis.model}, so a classification stays
     * explainable years later: "the model recommended X" means nothing without knowing which.
     */
    String model();

    /**
     * Tokens the model can take in one call. Used to warn when a prompt is about to overflow it,
     * which is a silent failure in Ollama (it drops the excess without saying so).
     */
    int contextWindow();
}
