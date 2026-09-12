# Temas a discutir en equipo

No son historias de desarrollo ni baches del DER: son decisiones que necesitan una charla antes de
poder convertirse en una card de Trello (o en nada). A diferencia de `der-gaps.md`, esto no son
correcciones al modelo de datos — son alcance de producto sin cerrar.

Cada entrada: qué se sabe, qué falta decidir, y qué bloquea mientras siga sin decidirse. Al pie hay
una sección aparte con lo que quedó huérfano al borrar el backlog de historias — ver ahí.

---

## ~~H0007 — Extracción de datos de documentos, alcance final~~ — ✅ cerrado (07/09/2026)

**Qué se hizo.** El modelo devuelve ahora `brand` y `model` separados de `itemDescription`, más una
lista genérica `details` (nombre/valor) para todo lo demás que el documento diga: nro. de factura,
nro. de serie, comercio. `DocumentInconsistencyEvaluator` cruza la marca contra el bien asegurado —
el chequeo que el IMEI no podía hacer fuera de Celulares, donde no hay IMEI contra qué comparar.
Prompt `extraccion-documento-v5.md`, migración `2026-09-07-datos-documento.sql`.

**Por qué híbrido y no todo genérico.** Un campo se gana una columna tipada cuando una **regla lo
compara**: la comparación necesita el tipo, no el texto —una fecha se resta, un importe se compara
con tolerancia, `affectedParty` es enum para que ninguna regla dependa de cómo el modelo redactó la
frase—. Y necesita que el nombre sea un contrato: en `details` el nombre es como lo llamó el modelo,
así que una regla que buscara ahí dejaría de encontrarlo el día que lo redacte distinto, **y
fallaría en silencio** — en este motor, una regla que no evalúa se lee como "no hay nada mal".
Cuando un detalle empiece a alimentar una regla, se promueve a columna.

**La edición del analista quedó fuera de alcance, a propósito.** La HU original la pedía, pero
choca de frente con la decisión #7: si el analista edita el dato extraído, el `document_analysis`
que fundamentó la recomendación cambia bajo los pies del log de auditoría. Y se perdería sola —
`document_analysis` se reemplaza en cada corrida, así que la próxima reclasificación se la lleva
puesta. Sostenerla pediría versionar la tabla y decidir si un valor corregido dispara
reclasificación, más caro que los otros dos puntos juntos. **Corresponde reescribir la HU** para
que diga esto en vez de prometer la edición.

---

## El ramo Tecnología Portátil de Provincia cubre un solo hecho generador

**Encontrado:** 05/09/2026, corriendo `init-multitenant.sql` + `seed-demo.sql` sobre un Postgres
limpio para verificar el seed de los criterios de Fast Track (H0038).

**Qué se sabe:** Provincia tiene una sola cobertura para Tecnología Portátil —*Daño accidental*— y
su lista negra excluye los hechos generadores 7 (Robo en vía pública) y 8 (Hurto). Como cada hecho
lo tiene que responder alguna cobertura, **un robo o un hurto de una notebook no lo cubre nadie**:
el selector del wizard filtra por esas mismas listas y no puede ofrecerlos, y si igual entrara un
expediente, el motor lo resolvería como exclusión de cobertura.

La intención parece haber sido otra: el encabezado del viejo `db/datos-aseguradoras.sql` (borrado el 12/09 junto con el esquema `aseguradora`) decía que el ramo
tiene las tres coberturas (Robo de celular, Hurto, Daño accidental), y la agenda documental
(`document_requirement`) ya tiene cargados los requisitos de los tres hechos.

Es el mismo agujero que tenía Celulares en BBVA con *Rotura accidental* y *Caída*, cerrado el
05/09 agregando la cobertura *Daño accidental* al ramo (H0038, ya implementada — commit
`2ebed337` y migración `2026-09-05-criterios-fast-track.sql`). Acá no se hizo lo mismo por dos
razones: **ningún expediente del fixture está afectado** —no hay denuncias de robo ni hurto de
equipo portátil— y las sumas
aseguradas y franquicias de las coberturas nuevas son un dato de negocio que no podemos inventar.

**Decidido (07/09/2026): Provincia se configura a gusto.** Los datos de esa compañía son nuestros,
de fixture, así que la objeción que frenaba el arreglo —"las sumas aseguradas y franquicias son un
dato de negocio que no podemos inventar"— no aplica. Se agregan las coberturas que falten, con los
valores que elijamos, en los dos ramos:

- **Tecnología Portátil**: sumar *Robo de celular* y *Hurto*, que es lo que deja el ramo sin
  responder por un robo o un hurto de notebook.
- **Celulares**: sumar *Rotura accidental* y *Caída*, el mismo agujero un ramo más abajo. Ojo que
  es solo de Provincia — BBVA quedó cerrado el 05/09.

**Se hace desde el panel del referente, sin tocar seed ni migración** (corregido el 07/09: hay ABM
completo de coberturas — `POST/PUT/DELETE /api/v1/coverages` en cases-service, cableado en la solapa
Coberturas). Son dos operaciones distintas:

- **Tecnología Portátil → crear** las coberturas *Robo de celular* y *Hurto*. La BD Aseguradora de
  Provincia ya las tiene contratadas en sus pólizas del ramo, así que apenas existan del lado de
  Arbiter `PolicyResyncScheduler` deja de saltearlas y arma solo las filas de `policy_coverage`. Eso
  apaga además el warning nocturno de abajo.
- **Celulares → editar** las exclusiones de la cobertura *Daño accidental*, que ya existe, para que
  deje de excluir *Rotura accidental* y *Caída*. Acá no hay cobertura que crear: la BD Aseguradora
  no modela esos dos como coberturas separadas, son hechos generadores que responde *Daño
  accidental*.

**El fixture sigue con el agujero.** Lo hecho por UI vive en la base desplegada; `seed-demo.sql`
nace igual que antes. Si alguien levanta de cero con `reset → init → seed` para una demo, vuelve.
Cerrarlo del todo pide igual la pasada por el seed — decisión aparte, y no urgente.

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

## ~~Una póliza con dos coberturas que responden al mismo hecho~~ — ✅ cerrado, no aplica

**Cerrado el 07/09/2026: no ocurre en el MVP.** Los ramos elegidos (Celulares, Tecnología Portátil)
tienen una relación lineal entre hecho generador y cobertura: cada hecho lo responde exactamente
una. El desempate de `PolicyCoverageResolver` —que hoy toma el orden en que las devuelve la
compañía— nunca se ejerce, así que no hay nada que decidir.

Queda anotado como decisión, no como pendiente: si alguna vez se suma un ramo donde dos coberturas
respondan al mismo hecho, el criterio de desempate vuelve a ser una pregunta abierta y el orden de
la compañía deja de alcanzar. **No reabrir sin ese caso.**

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

**Prioridad: última.** Decidido el 07/09/2026 — se mira después de todo lo demás.

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

## ~~`RulesRestAdapterTest` no valida query params~~ — ✅ resuelto (07/09/2026)

La nota que venía del backlog estaba desactualizada: el test
`everyCallCarriesTheQueryParamsRulesServiceRequires` ya existía. Pero verificaba la **presencia** del
parámetro y no su **valor** (`contains("claimCause=Robo")`), que pasa igual con un valor truncado o
a medio encodear — justo el modo de falla de un hecho generador con espacios y tilde.

Reforzado a comparar el valor decodificado completo, más un caso nuevo
(`claimCauseWithAccentsAndSpaces_arrivesIntactAtRulesService`) que lo asserta del lado del servidor,
que es donde se ve lo que rules-service realmente recibe. Verificado rompiendo el adapter a
propósito: con el `claimCause` truncado el test falla, y la URI que produce es exactamente la que la
aserción vieja dejaba pasar.
