# Temas a discutir en equipo

No son historias de desarrollo ni baches del DER: son decisiones que necesitan una charla antes de
poder convertirse en una card de Trello (o en nada). A diferencia de `der-gaps.md`, esto no son
correcciones al modelo de datos — son alcance de producto sin cerrar.

Cada entrada: qué se sabe, qué falta decidir, y qué bloquea mientras siga sin decidirse.

---

## Conversación con el asegurado

**Qué se sabe:** cuando el analista necesita pedir algo puntual (más documentación, una
aclaración), el sistema tiene que contemplar una conversación con el asegurado — por mail, por
chat dentro del portal, o los dos. No hay más especificación que esa frase.

**Qué falta decidir:**
- Canal: mail, chat en el portal, o ambos.
- Direccionalidad: ¿el asegurado puede responder y que le llegue al analista dentro de Arbiter, o
  el pedido se resuelve subiendo documentación sin conversación real de ida y vuelta?
- Alcance: ¿libre (el analista escribe lo que quiera) o atado a un ítem puntual de la
  `AgendaDocumental`?

**Bloquea:** no hay nada construido para esto — ni entidad, ni endpoint, ni pantalla. Es
greenfield completo; no conviene estimarla sin cerrar lo de arriba.

---

## H0015 tras la decisión de transiciones fijas

**Qué se sabe:** las transiciones de estado del expediente van a ser **fijas**, no configurables
por aseguradora — decisión confirmada del equipo (26/08). Esto contradice al paper §2.2 (*"cada
transición... se valida contra el flujo definido por la aseguradora"*) y §3.1 (*"modificar...
flujos sin desarrollo"*), y a la lectura original de H0015 (*"el referente define los estados
activos y las transiciones permitidas"*).

**Qué falta decidir:**
- ¿H0015 queda acotada a "estados activos" + "plazos por estado", sin "transiciones permitidas"?
- ¿Hay algo de "estados activos" que tampoco vaya a ser configurable, o eso se mantiene?
- Cómo se ajusta el texto del paper (§2.2, §3.1) para no afirmar algo que el sistema no va a
  hacer.

**Bloquea:** la card de "estados faltantes del ciclo de vida" ya está lista para Trello con el
alcance acotado (transiciones fijas en código) — esto no la bloquea. Lo que bloquea es escribir
bien H0015 y ajustar el paper antes de la defensa.

---

## H0007 — Extracción de datos de documentos, alcance final

**Qué se sabe:** H0031 (hecha el 18/08) ya cubre el corazón de la historia — el modelo extrae
datos tipados y el analista los ve en la solapa "Datos extraídos". Lo que no cubre: los campos no
son exactamente los que pedía la HU original (falta nro. de factura, marca/modelo/serie por
separado), la solapa es de solo lectura (no se puede corregir un valor mal leído), y la validación
es solo contra la denuncia, no contra los datos de la póliza.

**Qué falta decidir:** si el equipo considera que lo ya implementado alcanza (y corresponde
reescribir la HU para que describa eso), o si vale la pena una card más chica con los tres
puntos puntuales que faltan (campos exactos + edición + cruce contra póliza).

**Bloquea:** nada urgente — es la única de las tres sin apuro real, pero conviene cerrarla antes
de dar la HU por completa en la documentación.
