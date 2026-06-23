package ar.edu.utn.frba.arbiter.siniestros.adapters;

public interface DocumentAnalyzer {

    /**
     * Envía un documento adjunto (imagen/foto de factura, presupuesto, denuncia policial, etc.)
     * al modelo de visión y devuelve una transcripción/resumen de su contenido relevante,
     * lista para inyectar como texto en el prompt de clasificación.
     */
    String extractText(byte[] content, String contentType);
}
