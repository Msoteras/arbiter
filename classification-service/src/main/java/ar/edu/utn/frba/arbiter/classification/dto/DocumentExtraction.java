package ar.edu.utn.frba.arbiter.classification.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Lo que la pasada de visión saca de un adjunto: qué <b>dice</b> el documento y qué <b>parece</b>
 * el documento. Son dos cosas distintas y por eso viajan separadas.
 *
 * <p>Mezclarlas sería un error caro: si "la tipografía del encabezado no coincide con el resto"
 * llegara dentro de la transcripción, el clasificador la leería como contenido del documento —
 * como si el papel lo dijera. Separadas, el prompt puede presentarlas por lo que son: una
 * observación del modelo sobre la imagen, no un dato del documento.
 *
 * <p>Nace de D5: el modelo de visión ya tiene la imagen delante en la extracción, pero solo se le
 * pedía transcribir, así que la señal visual se perdía ahí. Mandarle la imagen al clasificador
 * habría costado miles de tokens de la ventana de 32k (decisión #2) y duplicado lo que el pipeline
 * de fraude ya hace por CLIP/pgvector (decisión #11); pedirle esto al paso que ya mira la imagen
 * no cuesta contexto extra.
 *
 * @param transcription  lo que el documento dice, en texto plano. Vacío si no se pudo leer.
 * @param visualFindings señales observables de manipulación o fabricación. <b>Vacío es lo normal</b>:
 *                       un documento común no tiene por qué generar ninguna. Nunca son concluyentes
 *                       — alimentan la lectura del analista, no una regla.
 * @param fields         los mismos datos, <b>tipados</b>, para que el código pueda compararlos.
 */
public record DocumentExtraction(String transcription, List<String> visualFindings, Fields fields) {

    /**
     * Los datos del documento como campos y no como prosa. Existe porque comparar párrafos no da un
     * resultado determinístico: para decir "el IMEI de la factura no es el del bien asegurado" hace
     * falta el IMEI como dato, no una frase que lo mencione. Es lo que destraba
     * {@code DocumentInconsistencyEvaluator} (D4b).
     *
     * <p>Todos nullable, y eso es lo normal: una foto del celular roto no tiene monto, una constancia
     * policial no tiene IMEI. Null significa "el documento no lo dice", nunca "no coincide" — un
     * campo ausente jamás debe leerse como una inconsistencia.
     *
     * @param documentDate    la fecha que figura en el documento (la del hecho o la de emisión)
     * @param amount          el importe total, si el documento tiene uno
     * @param itemDescription el bien que el documento nombra ("Samsung Galaxy A56")
     * @param imei            el IMEI que figura, normalizado a dígitos
     * @param affectedParty   quién sufrió el hecho según el documento (D9, {@code covers_family_group})
     */
    public record Fields(
            LocalDate documentDate,
            BigDecimal amount,
            String itemDescription,
            String imei,
            AffectedParty affectedParty
    ) {
        public static Fields none() {
            return new Fields(null, null, null, null, null);
        }
    }

    /**
     * Quién sufrió el hecho, según lo que dice el documento. Es el dato que necesita la regla de
     * {@code covers_family_group}: si la cobertura no alcanza al grupo familiar y el damnificado es
     * un familiar, el siniestro no está cubierto (D9).
     *
     * <p>Existe como enum y no como texto libre a propósito: el modelo <b>extrae</b> el hecho, el
     * código <b>decide</b> la regla. Una regla no puede depender de cómo el modelo redactó la frase.
     *
     * <p>{@link #DESCONOCIDO} es un valor de primera clase, no un error: si el documento no dice de
     * quién era el equipo, la regla no participa. Adivinar "titular" por defecto haría pasar casos
     * que no corresponden, y adivinar "familiar" rechazaría a gente por algo que nadie declaró.
     */
    public enum AffectedParty {
        TITULAR,
        FAMILIAR,
        TERCERO,
        DESCONOCIDO
    }

    public DocumentExtraction {
        transcription = transcription == null ? "" : transcription;
        visualFindings = visualFindings == null ? List.of() : List.copyOf(visualFindings);
        fields = fields == null ? Fields.none() : fields;
    }

    /** Solo texto, sin señales visuales ni campos — el resultado de un fallback o de un mock. */
    public static DocumentExtraction of(String transcription) {
        return new DocumentExtraction(transcription, List.of(), Fields.none());
    }
}
