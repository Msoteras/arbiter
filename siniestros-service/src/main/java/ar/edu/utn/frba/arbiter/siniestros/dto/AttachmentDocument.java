package ar.edu.utn.frba.arbiter.siniestros.dto;

/**
 * Un documento adjunto a clasificar junto con qué es (factura, denuncia policial,
 * foto del bien, etc.) — el OCR necesita ese tipo para saber cómo leerlo, y el
 * orquestador lo usa para decidir si hace falta extraerlo (depende de si el gate
 * de Fast Track ya resuelve el caso sin mirar documentos).
 */
public record AttachmentDocument(String type, byte[] content, String contentType) {}
