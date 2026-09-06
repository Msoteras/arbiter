# Temas a discutir en equipo

No son historias de desarrollo ni baches del DER: son decisiones que necesitan una charla antes de
poder convertirse en una card de Trello (o en nada). A diferencia de `der-gaps.md`, esto no son
correcciones al modelo de datos — son alcance de producto sin cerrar.

Cada entrada: qué se sabe, qué falta decidir, y qué bloquea mientras siga sin decidirse. Al pie hay
una sección aparte con lo que quedó huérfano al borrar el backlog de historias — ver ahí.

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

---

## El ramo Tecnología Portátil de Provincia cubre un solo hecho generador

**Encontrado:** 05/09/2026, corriendo `init-multitenant.sql` + `seed-demo.sql` sobre un Postgres
limpio para verificar el seed de los criterios de Fast Track (H0038).

**Qué se sabe:** Provincia tiene una sola cobertura para Tecnología Portátil —*Daño accidental*— y
su lista negra excluye los hechos generadores 7 (Robo en vía pública) y 8 (Hurto). Como cada hecho
lo tiene que responder alguna cobertura, **un robo o un hurto de una notebook no lo cubre nadie**:
el selector del wizard filtra por esas mismas listas y no puede ofrecerlos, y si igual entrara un
expediente, el motor lo resolvería como exclusión de cobertura.

La intención parece haber sido otra: el encabezado de `db/datos-aseguradoras.sql` dice que el ramo
tiene las tres coberturas (Robo de celular, Hurto, Daño accidental), y la agenda documental
(`document_requirement`) ya tiene cargados los requisitos de los tres hechos.

Es el mismo agujero que tenía Celulares en BBVA con *Rotura accidental* y *Caída*, cerrado el
05/09 agregando la cobertura *Daño accidental* al ramo (H0038, ya implementada — commit
`2ebed337` y migración `2026-09-05-criterios-fast-track.sql`). Acá no se hizo lo mismo por dos
razones: **ningún expediente del fixture está afectado** —no hay denuncias de robo ni hurto de
equipo portátil— y las sumas
aseguradas y franquicias de las coberturas nuevas son un dato de negocio que no podemos inventar.

**Qué falta decidir:**
- Si el producto de Provincia efectivamente cubre robo y hurto de equipo portátil, o si esa
  restricción es real y lo que hay que corregir es el encabezado del script y la agenda documental.
- Con qué suma asegurada y franquicia entra cada cobertura nueva en cada póliza del ramo.
- Si Provincia además debería cubrir *Rotura accidental* y *Caída* en Celulares: hoy tampoco las
  cubre ninguna cobertura suya, y tampoco hay expedientes afectados. Es la misma decisión, un ramo
  más abajo — y ojo, es de Provincia: BBVA ya quedó cerrado.

**Bloquea:** nada hoy. Bloquearía a cualquiera que quiera demostrar un robo o un hurto de notebook
en Provincia, que es un caso perfectamente razonable de mostrar en la defensa.

**Se va a ver todas las noches hasta que se decida.** La BD Aseguradora de Provincia sí tiene una
cobertura de robo en dos pólizas de Tecnología (bajo el nombre "Robo de celular", que es el único
literal de robo que admite el CHECK de `cobertura.nombre`). Como el ramo no la tiene configurada del
lado de Arbiter, `PolicyResyncScheduler` la va a saltear y dejar el warning *"coverage(s) not
configured on this tenant for its branch, skipped: [Robo de celular]"* en cada corrida. Es
deliberado: sin el chequeo de ramo, el sync le colgaría a una notebook la cobertura de Celulares,
con sus plazos y su carencia.

---

## Una póliza con dos coberturas que responden al mismo hecho — cómo desempatar en el alta

**Encontrado:** 01/09/2026, cerrando el agotamiento por monto acumulado por cobertura. Venía de
`gap-dominio-bbva.md` §12, que se borró al quedar todo lo demás resuelto.

**Qué se sabe:** la analista marcó que la relación hecho generador ↔ cobertura **puede no ser
lineal**: una póliza podría tener dos coberturas que respondan por el mismo hecho. No afecta al
agotamiento por monto —el histórico trae la cobertura imputada, no se infiere— pero sí al **alta**:
ahí `PolicyCoverageResolver` desempata por el orden en que las devuelve la compañía, que es un
criterio accidental, no una decisión de negocio.

Importa porque la cobertura elegida es la que aporta los parámetros con los que se evalúan las
reglas duras del expediente: carencia, plazo de denuncia, tope de eventos, franquicia y suma
asegurada.

**Qué falta decidir:** entre tres opciones —que el referente declare una prioridad entre coberturas
del mismo hecho, que elija el analista al revisar, o dejar el orden de la compañía como está y
documentarlo como criterio explícito.

**Bloquea:** nada hoy. En el seed actual ninguna póliza tiene dos coberturas que respondan al mismo
hecho, así que el desempate no se ejerce nunca. Bloquearía en cuanto una compañía real cargue ese
caso.

---

## Reservas (SPL) — ¿control transversal o fuera de alcance?

**Encontrado:** 31/08/2026, comparando contra el procedimiento interno de BBVA
(`Siniestros_NSIN001`). Venía de `gap-dominio-bbva.md` §3.

**Qué se sabe:** no existe ninguna entidad, columna ni servicio de reservas en el repo. El doc
fuente lo trata como **control transversal**, no como monto de pago: la reserva se abre con la
denuncia, se ajusta con cada valuación y se cierra en cada estado terminal (§8, §10) — es una de
sus aserciones de test más citables.

Linda con liquidación, que el propio doc de dominio confirma **fuera del alcance** de Arbiter (§2).
Pero como control (abierta/cerrada, sin el monto) es barato de modelar.

**Qué falta decidir:** si entra como flag booleano por expediente —cerrando una invariante de la
máquina de estados que hoy no tiene dónde apoyarse— o si queda fuera de alcance junto con
liquidación y se defiende explícitamente como tal.

**Bloquea:** el estado `DENUNCIA DE HECHO (RC)` del proceso real, que el doc define como "activo sin
reserva" y por lo tanto no se puede modelar sin resolver esto antes.

---

# Heredado del backlog de historias

Las dos entradas que seguían de `historias-enhancements.md` (borrado el 06/09 al quedar sus cards
cargadas en Trello) y que **no eran historias**, así que no viajaron con el resto.

## Pólizas colectivas — ✅ decidido: no se modelan

Decidido al planificar el sprint 9 (26/08/2026). Cada póliza sigue siendo individual, 1:1 con su
certificado, y `Tomador` + `N° de certificado` quedan afuera de la ficha del expediente tal como
están hoy.

Queda anotado acá porque es una decisión de alcance que nadie más registra —el código no la
documenta, la documenta su ausencia— y conviene poder citarla en la defensa en vez de tener que
reconstruir por qué faltan esos dos campos. **No reabrir sin un caso nuevo.**

## `RulesRestAdapterTest` no valida query params

**Qué se sabe:** `classification-service/.../adapters/RulesRestAdapterTest.java` verifica que el
adapter llame al endpoint correcto, pero no qué query params le manda. Esa es exactamente la razón
por la que un bug de parámetros pasó desapercibido en su momento.

No es bloqueante y no rompe nada hoy: es deuda de test, y de la clase de hueco que se repite —el
test pasa, el contrato no se verifica.

**Qué falta decidir:** si vale una card propia o se arregla de paso la próxima vez que alguien toque
el adapter. Es chico para una card sola, pero llevarlo de arrastre significa que se olvida.
