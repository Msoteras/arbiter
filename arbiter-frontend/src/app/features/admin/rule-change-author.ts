/**
 * Saca de un motivo del historial quién hizo el cambio.
 *
 * El autor **no es un campo**: `changed_by` se guarda siempre en `null` y el backend mete al actor
 * dentro del texto de `reason` ("Fast Track actualizado por ana@bbva.com"). Hasta que eso se
 * arregle (card H0034), esto es lo único que hay para responder "quién" — y sin el quién, la
 * auditoría deja de servir para lo que existe.
 *
 * El resto del motivo no se muestra: todos siguen la forma `<qué cambió> por <quién>`, y el qué ya
 * está en el título y el alcance de la fila. Repetirlo era ruido.
 *
 * Se busca el ÚLTIMO separador y no el primero porque el texto que lo precede puede contener uno
 * ("Hechos generadores cubiertos actualizados por X" no, pero un motivo futuro sí podría).
 */

/** Los separadores que usó el backend, en español y en el inglés que llegó a escribir. */
const AUTHOR_SEPARATORS = [' por ', ' by '];

export function ruleChangeAuthor(reason: string | null | undefined): string | null {
  if (!reason) {
    return null;
  }
  const cut = AUTHOR_SEPARATORS
    .map((separator) => ({ separator, at: reason.lastIndexOf(separator) }))
    .filter((candidate) => candidate.at >= 0)
    .sort((a, b) => b.at - a.at)[0];

  if (!cut) {
    return null;
  }
  const author = reason.slice(cut.at + cut.separator.length).trim();
  // Sin autor legible se devuelve null en vez de una cadena vacía: la vista decide no mostrar nada,
  // que es más honesto que un "por" colgado o un espacio en blanco donde debería ir un nombre.
  return author.length > 0 ? author : null;
}
