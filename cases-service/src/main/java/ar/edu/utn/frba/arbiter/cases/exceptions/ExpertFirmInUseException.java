package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * El perito ya recibió derivaciones, así que no se borra: se desactiva. Borrarlo dejaría
 * peritajes apuntando a una fila que no existe, y aunque el nombre y el mail están copiados en
 * cada peritaje, el referente perdería el rastro de a quién le derivó y por qué.
 */
public class ExpertFirmInUseException extends RuntimeException {

    public ExpertFirmInUseException(Long expertFirmId) {
        super("El perito " + expertFirmId + " ya tiene peritajes registrados: desactivalo en vez de borrarlo");
    }
}
