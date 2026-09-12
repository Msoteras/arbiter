package ar.edu.utn.frba.arbiter.cases.dto;

import java.util.List;

/**
 * La primera tanda de documentos: qué se le pide al asegurado al registrar la denuncia.
 *
 * <p>Es lo que el carril rápido exige para la cobertura que responde por el hecho generador
 * denunciado. La agenda documental completa —el contrato de "expediente completo"— se le pide
 * después, y solo si el siniestro no entra al carril rápido: el motor la evalúa al clasificar y el
 * expediente pasa a {@code AWAITING_DOCUMENTATION} con lo que falte.
 *
 * @param documentTypes los tipos a pedir, en el vocabulario de la agenda ({@code police_report},
 *                      {@code purchase_proof}, …). Vacío significa que no se le pide ninguno.
 * @param fastTrackOnly {@code true} cuando la lista es la del carril rápido; {@code false} cuando la
 *                      aseguradora no configuró ninguna y se cae a la agenda completa — sin lista no
 *                      habría nada que pedir, y el expediente llegaría al analista sin un papel.
 */
public record IntakeDocumentsResponse(List<String> documentTypes, boolean fastTrackOnly) {
}
