package ar.edu.utn.frba.arbiter.classification.adapters;

public interface DocumentAnalyzer {

    /**
     * Sends an attachment (image/photo of an invoice, repair quote, police report, etc.)
     * to the vision model and returns a transcription/summary of its relevant content,
     * ready to inject as text into the classification prompt.
     */
    String extractText(byte[] content, String contentType);
}
