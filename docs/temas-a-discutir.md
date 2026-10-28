# Temas a discutir en equipo

No son historias de desarrollo ni baches del DER: son decisiones que necesitan una charla antes de
poder convertirse en una card de Trello (o en nada). A diferencia de `der-gaps.md`, esto no son
correcciones al modelo de datos — son alcance de producto sin cerrar.

Cada entrada: qué se sabe, qué falta decidir, y qué bloquea mientras siga sin decidirse. Al pie hay
una sección aparte con lo que quedó huérfano al borrar el backlog de historias — ver ahí.

---

## ~~H0007 — Extracción de datos de documentos, alcance final~~ — ✅ cerrado (07/09/2026), salvo la edición

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

La edición del analista no entró en este cierre: sigue en alcance y se trata en la entrada de abajo.

---

## Edición de los datos extraídos por el analista — en alcance, sin implementar

**Corregido el 28/09/2026.** Esta entrada decía que la edición había quedado fuera de alcance y que
había que reescribir la HU. No es así: el analista **sí** puede corregir un dato extraído si le
parece que el modelo lo leyó mal. Lo que pasa es que todavía no está implementado.

**Qué hay que resolver al implementarla.** Son los dos problemas que se habían usado para dejarla
afuera; ahora son requisitos de diseño:

- **Auditoría (decisión #7).** Si la corrección pisa `document_analysis`, cambia el dato que
  fundamentó la recomendación y el log queda mintiendo. La corrección tiene que guardarse aparte
  (quién, cuándo, valor anterior y nuevo) sin tocar lo que leyó el modelo.
- **Reclasificación.** `document_analysis` se reemplaza en cada corrida, así que una corrección
  guardada ahí se pierde en la próxima. Además hay que decidir si corregir un dato dispara una
  reclasificación o solo queda como constancia para el analista.

**Qué bloquea:** nada hoy. El analista ve el dato mal leído y lo tiene que tener en cuenta a mano.

---

## El score de riesgo no suma lo que encuentra el LLM — abierto (27/10/2026)

**Qué se sabe.** El score sale solo de factores de datos: monto sobre suma asegurada, frecuencia de
siniestros, estado de la póliza, inconsistencias entre documentos y similares. Lo que el LLM
encuentra al leer el caso no entra en el cálculo.

Caso que lo muestra: el expediente #64 (Valentín Aguirre, hurto en un bar de San Telmo). La
constancia de última conexión ubica el celular, después del hurto, bajo la antena del domicilio del
asegurado. El LLM lo detecta y lo pone en sus tres razones, y recomienda revisión manual. El score
queda en 20/100, banda baja. El analista ve un riesgo bajo al lado de una contradicción grave.

**Qué falta decidir.**

- Si el score tiene que reflejar la lectura del LLM, o si se mantiene como medida de datos objetivos
  y la recomendación del LLM va aparte. La separación sigue la implicancia de diseño 5 de
  `CLAUDE.md`: las reglas evalúan condiciones objetivas y el LLM aporta la lectura interpretativa.
- Si entra, cómo: un factor más en `scoring_configuration`, con un peso que configure cada
  aseguradora, alimentado por la recomendación o por las inconsistencias que marque el LLM.
- Si no entra, cómo se muestra en la UI para que un score bajo no se lea como "caso limpio".

**Qué bloquea:** nada. El analista ve las razones del LLM en la solapa Análisis, pero el score y la
banda que encabezan el expediente pueden contradecirlas.

---

## ~~El ramo Tecnología Portátil de Provincia cubre un solo hecho generador~~ — ✅ cerrado (23/10/2026)

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
- **Celulares → crear** la cobertura *Daño accidental*, sin excluir *Rotura accidental* ni *Caída*.
  ~~Editar sus exclusiones, que ya existe~~: verificado en Railway el 25/09/2026, Provincia tiene en
  Celulares solo *Robo de celular* y *Hurto*. La BD Aseguradora no modela esos dos hechos como
  coberturas separadas: los responde *Daño accidental*.

**Estado al 25/09/2026: sin hacer.** En Railway, Tecnología Portátil sigue solo con *Daño
accidental* y Celulares sin él.

**Actualización 10/10/2026.** La cobertura "Robo de celular" pasó a llamarse *Robo* y el hecho
*Daño accidental* de Tecnología Portátil se partió en *Rotura accidental* y *Caída*
(`2026-10-10-nombres-de-cobertura.sql`). Crear la cobertura de robo de Tecnología Portátil desde el
panel antes era imposible, porque el nombre era único en toda la aseguradora; ahora lo es por ramo.
Para que cruce con las pólizas hay que crearla como "Robo de celular" (así queda su nombre externo)
y renombrarla después.

**Cerrado en el fixture (11/10/2026).** `seed-demo.sql` ahora le carga a Provincia *Robo* y *Hurto*
en Tecnología Portátil y *Daño accidental* en Celulares, con sus exclusiones, Fast Track y reglas
duras: cada hecho generador de los dos ramos lo responde exactamente una cobertura. Una póliza de
cada ramo las contrata (`POL-TEC-2026-311` suma Hurto, `POL-CEL-2026-777` suma Daño accidental).

**Falta en Railway.** El seed solo corre sobre una base vacía: en la base desplegada las tres
coberturas siguen sin existir hasta que se creen desde el panel o se haga `reset → init → seed`.

**Actualización 23/10/2026.** En Railway, Celulares ya tiene "Daño accidental de celular", creada
desde el panel con sus reglas. Faltan Robo y Hurto en Tecnología Portátil: los carga
`2026-10-23-coberturas-de-robo-y-hurto-en-tecnologia-provincia.sql`, con sus exclusiones, reglas
duras y Fast Track. Aplicada en Railway el 23/10 (coberturas 9 y 10).

**En Railway se va a ver todas las noches hasta que se creen.** La BD Aseguradora de Provincia sí tiene una
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

## ~~Reservas (SPL) — ¿control transversal o fuera de alcance?~~ — ✅ decidido: fuera de alcance (22/09/2026)

**Qué se decidió:** las reservas no se contemplan. Ni como monto ni como flag abierta/cerrada por
expediente. Si en la defensa sale el tema, se defiende así: la reserva (SPL, siniestros pendientes
de liquidación) es la provisión contable que la compañía abre al recibir la denuncia y ajusta hasta
cerrarla. Es un control del área contable de la aseguradora y no del circuito de análisis y
clasificación que cubre Arbiter.

**Consecuencia asumida:** el estado `DENUNCIA DE HECHO (RC)` del proceso real ("activo sin reserva")
no se modela. **No reabrir sin un caso nuevo.**

Lo que sigue queda como registro de cómo se llegó a la decisión.

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

## ~~Alcance de la cobertura: ¿de dónde sale "grupo familiar"? Y "agota la cobertura" sobraba~~ — ✅ decidido: de la póliza (15/10/2026)

**Decidido el 15/10/2026: opción 1.** El motor lee `poliza.cubre_grupo_familiar` de la BD
Aseguradora (`InsuredPolicy.coversFamilyGroup`), y queda congelado en el snapshot de la póliza con
el resto de la respuesta. Se borró `coverage.covers_family_group` con
`2026-10-15-borrar-grupo-familiar-de-cobertura.sql`, junto con su campo en los DTOs de cases, rules
y classification. El referente no configura nada. La BD Aseguradora define la columna `NOT NULL
DEFAULT FALSE`, así que toda aseguradora integrada la informa. Si alguna vez llega vacía, la regla no
se evalúa.

Lo que sigue queda como registro de cómo se llegó a la decisión.

**Encontrado:** 14/10/2026, revisando la solapa Reglas → Coberturas. El checkbox "Alcanza al grupo
familiar" ya no está en la cobertura (`feature/ux-enhancements-reglas`). La columna y la regla del
motor siguen como estaban.

**Qué se sabe:**

- `coverage.covers_family_group` se carga por cobertura. Lo evalúa `CoverageScopeEvaluator` como
  regla dura (`COVERS_FAMILY_GROUP`): si un documento dice que el damnificado es un familiar y la
  cobertura no lo alcanza, frena el Fast Track y deja su fila en `rule_result`.
- Sin el checkbox, nadie puede cambiar ese valor desde el panel. El motor lo sigue evaluando.
- **La BD Aseguradora ya trae el dato, por póliza:** `poliza.cubre_grupo_familiar`. Arbiter no lo
  lee: el motor usa solo el de la cobertura (ver `BusinessRules` y `CoverageLimitsDto`).
- **El dato de la póliza no es uniforme dentro de un ramo.** En el seed, BBVA tiene 12 pólizas de
  Celulares: 8 alcanzan al grupo familiar y 4 no. Sale del producto que contrató cada asegurado,
  no del ramo.
- **Hoy los dos datos se contradicen.** Las tres coberturas de Celulares de BBVA dicen que no
  alcanzan al grupo familiar. En esas 8 pólizas, un familiar damnificado frena el Fast Track aunque
  el contrato lo cubra.

**Qué falta decidir: de dónde sale el dato.** La idea inicial era pasarlo al ramo y mostrarlo en
la solapa "Exclusiones comunes". Con lo de la póliza hay tres opciones:

1. **De la póliza, sin configuración en Arbiter** (la recomendada). El snapshot de la póliza guarda
   `cubre_grupo_familiar` y `CoverageScopeEvaluator` lo lee de ahí. Se borra
   `coverage.covers_family_group` y el referente no configura nada. Es el contrato concreto, así
   que no puede contradecirse con la póliza. Se conserva la regla dura y la auditoría.
2. **Switch del ramo.** Columna `branch.covers_family_group`, con migración desde `coverage`, y un
   switch en la solapa "Exclusiones comunes". Se conserva la regla dura, pero repite el problema
   de hoy un nivel más arriba: un solo valor para pólizas que difieren. Habría que decidir qué
   manda cuando la póliza y el ramo no coinciden.
3. **Texto libre en la lista de exclusiones comunes.** Cero código, pero esa lista va al prompt del
   LLM (`PromptBuilder`) y el motor no la evalúa. Se pierde la regla dura `COVERS_FAMILY_GROUP`, y
   el LLM no corre en Fast Track, así que un familiar damnificado pasaría por el Fast Track. Choca
   con la implicancia #5 de `CLAUDE.md`: las exclusiones objetivas las evalúan reglas, no el LLM.

Para la opción 1 hay que confirmar que toda aseguradora que se integre informa este dato por
póliza. Si alguna no lo trae, hace falta un valor por defecto, y ese sí puede vivir en el ramo.

**✅ `claim_exhausts_coverage` borrado (14/10/2026).** Era redundante con el tope de siniestros
(`max_events_per_year`): un tope de 1 dice lo mismo. Se fueron la columna, el campo de los DTOs y
la regla `CLAIM_EXHAUSTS_COVERAGE` del motor. La migración
`2026-10-14-borrar-un-siniestro-agota-la-cobertura.sql` le pone tope 1 a toda cobertura que tenía la
marca prendida, y le prende la regla `MAX_EVENTS_YEAR` si no la tenía. Las filas viejas de
`rule_result` se siguen leyendo en la trazabilidad.

---

## ~~¿El analista puede pedir un documento sin que lo sugiera la banda?~~ — ✅ decidido (23/10/2026)

**Qué se decidió:** sí, el analista puede pedir documentación por su cuenta, porque no sabemos todos
los documentos que se pueden llegar a necesitar. Son dos pasos separados:

1. El analista pasa el expediente a "Falta documentación" y marca los documentos que faltan. Si el
   documento no está en el catálogo, elige el tipo genérico "Otro documento".
2. El analista le explica al asegurado por el chat qué documento concreto necesita.

Desde que el expediente pasa a "Falta documentación", el plazo del art. 56 se congela, igual que con
un documento de la agenda: ese tiempo corre por cuenta del asegurado. Cuando el asegurado sube lo que
falta, el expediente se reclasifica. El pedido queda en el historial del expediente.

**Implementado (23/10/2026), sin commitear:**

- Acción "Pedir documentación" en el detalle del expediente, con un paso de confirmación antes de
  enviar. El chat sigue sin mover el expediente (`CaseMessageService`).
- Lo pedido queda en `cases.requested_documents`
  (`2026-10-23-documentos-pedidos-por-el-analista.sql`). El historial guarda el analista, los
  documentos y los motivos; el asegurado no ve ni los motivos ni la observación.
- "Otro documento" **no** se sumó a `document_type`: si estuviera en el catálogo, el referente lo
  podría poner en la agenda de un ramo. Cada uno viaja como `other_1`, `other_2`…, nombrado por la
  descripción del analista.
- El asegurado tiene que subir todo lo pendiente para enviar, sea el pedido del analista o lo que
  falta de la agenda.
- El modelo lee la descripción y el motivo en una sección propia del prompt (`classification-v8`),
  fuera del bloque que el prompt marca como texto del asegurado.
- Propuesta de Mar (24/10/2026): la reclasificación ya no vuelve a leer con el modelo los
  documentos que tienen una lectura completa guardada en `document_analysis`. Solo se leen los
  nuevos. Las reglas, el puntaje y la clasificación se rehacen siempre con todo junto.

Lo que sigue queda como registro.

**Encontrado:** 23/10/2026, armando la historia "Documentación extra según el riesgo del
expediente". En esa historia el sistema sugiere los documentos que exige la banda y el analista
confirma el pedido. Quedaba afuera el caso de un documento que la banda no exige. La postura de Mar
(23/10/2026) fue que el analista tiene que poder pedirlo, y que si el documento es muy distinto a
los del catálogo, alcanza con que quede registrado que se pidió.

---

## ~~¿El puntaje de riesgo puede bajar en una reclasificación?~~ — ✅ decidido: no baja (24/10/2026)

**Qué se decidió:** la opción 1. El expediente conserva la banda y el puntaje más altos que tuvo:
una reclasificación los puede subir, nunca bajar. Si se piden documentos de más es porque el riesgo
es alto, y un documento nuevo no lo vuelve bajo. `risk_analysis` sigue guardando lo que calculó cada
corrida; lo que no baja es lo que el expediente muestra y lo que decide qué documentos se sugieren.

**Flujo que se decidió junto con esto (24/10/2026):** cuando la clasificación detecta que falta
documentación, el pedido ya no sale solo. El expediente queda en revisión del analista con los
documentos sugeridos (los que faltan de la agenda para su banda) y el analista elige cuáles se piden.
Recién al confirmar pasa a "Falta documentación". Después de un pedido, la reclasificación no vuelve a
exigir la agenda; si la banda sube, se sugieren solo los documentos de la banda nueva. En Fast Track
no se pide documentación.

Lo que sigue queda como registro.


**Encontrado:** 23/10/2026, armando la historia "Documentación extra según el riesgo del
expediente".

**Qué se sabe:** `RiskScoringService` recalcula el puntaje desde cero en cada clasificación. En la
historia de la banda, el expediente se reclasifica cuando el asegurado sube el documento pedido.
Ejemplo:

1. Primera clasificación: puntaje 82, banda Alto. Se pide la factura extra.
2. El asegurado la sube y el expediente se reclasifica: puntaje 55, banda Medio.
3. El detalle muestra "Riesgo medio" con un documento pedido por riesgo alto.

**Opciones:**

1. **El puntaje no baja** (la postura inicial). Si el cálculo nuevo da menos, se conserva el
   anterior. Puede subir, y si la banda nueva exige otros documentos, se genera una sugerencia
   nueva. La banda del detalle y la del pedido siempre coinciden. En contra: si un documento nuevo
   aclara una sospecha (por ejemplo, el IMEI de la factura coincide con la póliza), el puntaje no
   lo refleja.
2. **El puntaje es el último cálculo.** El detalle muestra la banda actual y el historial registra
   la banda que originó cada pedido. Solo la agenda queda congelada: un documento pedido no deja de
   exigirse aunque la banda baje. En contra: el analista ve una banda menor que la del pedido.

**Qué cambia con la opción 1:** hay que guardar el puntaje más alto y que la reclasificación lo tome
como mínimo. El reporte de fraude y el tablero agrupan por banda, así que cuentan el puntaje
conservado y no el último cálculo.

**Qué bloquea:** el criterio de la historia de la banda sobre qué pasa con lo exigido cuando la
reclasificación da un puntaje menor.

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
---

## ~~La franquicia en una reparación deja casi todo en cero~~ — ✅ decidido: configurable por cobertura (28/09/2026)

**Decisión:** cada cobertura dice si la franquicia se calcula sobre la **suma asegurada** o sobre el
**monto a indemnizar** (lo que efectivamente se indemniza: valor de reposición o presupuesto, con
su tope). Lo elige el referente en Reglas → Coberturas, y la liquidación guarda cuál se usó. Es
decisión de cada aseguradora según su póliza: la de celulares de BBVA dice "10% de la suma
asegurada"; buena parte del mercado lo toma "del siniestro". Configuración inicial: Robo y Hurto de
BBVA sobre la suma asegurada; Daño accidental de las dos y Robo y Hurto de Provincia sobre el
siniestro. Migración `2026-09-28-base-de-la-franquicia.sql`, aplicada en Railway.

Lo que sigue es el registro original del problema.

**Encontrado:** 06/09/2026, implementando la fórmula de reparación (bloque 3 de la determinación
del monto a pagar).

**Qué se sabe:** la franquicia es un **porcentaje de la suma asegurada**, no del monto que se paga.
Es la lectura literal de las dos pólizas que relevamos y del ejemplo trabajado del manual de
Celulares ($300.000 asegurados → $30.000 de franquicia). Así está implementado en
`SettlementCalculator`, y así da el número del ejemplo del manual.

En pérdida total la regla es razonable: se descuenta una fracción de lo mismo que se indemniza. En
**reparación** no, porque el techo ya no es la suma asegurada sino el presupuesto:

| Suma asegurada | Franquicia 10% | Presupuesto de reparación | Se paga |
|---|---|---|---|
| $1.300.000 | $130.000 | $80.000 (cambio de pantalla) | **$0** |
| $1.300.000 | $130.000 | $200.000 | $70.000 |

Una pantalla rota —el siniestro más común del ramo— entra de lleno en el primer caso: la cobertura
de daño accidental no paga nunca nada por debajo de $130.000.

**Qué falta decidir:** si en la práctica las coberturas de daño calculan la franquicia sobre el
**presupuesto** en vez de sobre la suma asegurada. Nuestras dos pólizas no lo distinguen: las dos
hablan de una sola franquicia, y ninguna de las dos contempla explícitamente la reparación.

**Qué bloquea:** nada del desarrollo — la fórmula está implementada y anda. Bloquea poder decir que
el monto de una reparación es el que la compañía pagaría. Si la respuesta es "sobre el
presupuesto", el cambio es una línea en `SettlementCalculator` —la que hoy hace
`percentageOf(sumInsured, deductibleRate(...))`— más un interruptor por cobertura, como los que ya
tiene.

---

## ~~En Fast Track nadie compara el relato con el hecho generador declarado~~ — ✅ decidido (22/09/2026)

**Qué se decidió:** la segunda opción de abajo. La extracción devuelve el hecho que narra cada
documento como campo tipado (`describedClaimCause`, un nombre del catálogo del ramo; prompt
`extraccion-documento-v6.md`), se persiste en `document_analysis.described_claim_cause`, y
`ClaimCauseConsistencyEvaluator` lo compara por código contra el declarado. **Avisa, no bloquea:** el
caso conserva su clasificación, Fast Track incluido, y el analista recibe el motivo en los factores,
una fila `CLAIM_CAUSE_MATCH` en `rule_result` y una tarjeta "Para revisar antes de resolver" en el
detalle del expediente. El tablero de "reglas que frenaron" no lo cuenta, porque no frenó nada.

**Encontrado:** 22/09/2026, con la mutación `relato-hurto` (`docs/postman/test-docs/mutaciones/`).

**Qué se sabe:** el asegurado elige el hecho generador de un selector y escribe el relato aparte. Si
eligió "Robo en vía pública" pero cuenta que dejó el celular sobre la mesa de un café y al volver no
estaba, eso es un hurto, y la cobertura de robo de BBVA excluye el hurto (regla 21). Quien detecta la
contradicción es el LLM de clasificación (sección "Consistencia del relato" del prompt), y el
orquestador la convierte en `LLM_NO_RECOMIENDA_APROBAR` si el hecho que describe el relato está
excluido.

Pero ese LLM **solo corre si el caso no entra en Fast Track**. En el carril rápido el gate extrae el
acta y la factura con el modelo de visión, y lo único que verifica es que tengan texto: no lee qué
dicen. Así que el caso entra en Fast Track aunque el acta diga en la carátula `HURTO (art. 162)`.
El analista igual decide (decisión #5), pero llega con la etiqueta de "todo en regla".

**Qué falta decidir:** si alcanza con eso — el Fast Track agiliza, y el analista puede leer el acta
—, o si el carril rápido tiene que mirar al menos el hecho. Opciones, de más barata a más cara:

- Dejarlo así y que el analista lo vea en el acta.
- Pedirle a la extracción del acta (que ya corre en el gate) el hecho generador que describe, como
  campo tipado, y compararlo por código contra el declarado: mismo patrón que `affectedParty`, que
  ya bloquea el Fast Track desde el acta. No suma una llamada al modelo.
- Correr el chequeo de consistencia del LLM también en Fast Track: una inferencia más por caso,
  justo en el camino que existe para ser rápido.

**Qué bloquea:** nada del desarrollo. Mientras no se decida, un hecho mal declarado a propósito
entra por el carril rápido.

**Actualización 25/09/2026.**

- **Criterio robo/hurto** (prompts `extraccion-documento-v7` y `classification-v6`): un tirón o
  arrebato ya es robo, aunque no haya lesiones ni forcejeo narrado; hurto es sin contacto con la
  persona, que se da cuenta después. El modelo solo elige un hecho cuando el relato es clarísimo;
  ante la duda devuelve `null` y lo mira el analista. Con el v6, Gemini leía "Hurto" en un acta
  caratulada ROBO (caso #46).
- **Una lectura rota no sostiene un Fast Track.** Cada lectura guarda
  `document_analysis.extraction_status`. Si un documento exigido por el gate queda `FAILED`, no
  hay Fast Track; si queda `PARTIAL` (solo se rescató el texto), pasa con un factor que sugiere
  revisión manual.

---

## ~~`document_inconsistency` no tiene peso, así que no corre~~ — ✅ resuelto (25/09/2026)

**Qué se hizo:**

- Se arreglaron **dos** falsos positivos antes de darle peso. El del importe estaba anotado: ahora
  se compara solo contra el documento que fija el monto (el presupuesto en daño, la factura en
  robo o hurto). El otro no: la fecha marcaba la factura de compra, que siempre es anterior al
  hecho, así que con peso subía el score de **todos** los robos. La factura quedó afuera de ese
  chequeo.
- Peso **0,40** en las dos aseguradoras: en Railway, cargado desde el panel del referente; en
  `init-multitenant.sql`, para las bases nuevas. `BaselineRulesAdapter` lo sigue dejando afuera a
  propósito: fuera de nuestras dos aseguradoras, activarlo es decisión de cada compañía.
- `purchase_to_report_time` (27/09/2026): ahora toma la fecha de compra de la factura, no el alta de
  la póliza, que ya cubren la carencia y `FT_POLICY_AGE`. Sin factura con fecha, no se evalúa. Peso
  **0,25** en `init-multitenant.sql`; en Railway hay que cargarlo desde el panel del referente.

Lo que sigue queda como registro.

**Encontrado:** 22/09/2026, armando las mutaciones de `docs/postman/test-docs/mutaciones/`.

**Qué se sabe:** `DocumentInconsistencyEvaluator` compara lo que dicen los documentos contra el
siniestro y la póliza: el IMEI, la marca y el modelo, el importe (con 10% de tolerancia), una fecha
de documento más de 7 días anterior al hecho, y la fecha del acta contra la que declaró el
asegurado. Pero `RiskScoringService` recorre solo los factores que tienen fila en `factor_weight`, y
**ninguna de las dos aseguradoras le asignó peso**: el evaluador directamente no se ejecuta. Lo mismo
pasa con `purchase_to_report_time`.

No es un bug del motor: el seed de `init-multitenant.sql` carga seis factores (`amount_ratio`,
`claim_frequency`, `policy_standing`, `image_reuse`, `image_web_match`, `fraud_history`) y deja
afuera esos dos. El panel de scoring del referente sí los ofrece (grupo "Documentos e imágenes"),
así que cargarles peso no requiere tocar código.

Hoy, un IMEI que no es el de la póliza, una factura de otra marca o una constancia fechada antes
del hecho **no mueven el score**. Solo quedan en `document_analysis`, y fuera de Fast Track, en lo
que lea el LLM.

**Qué falta decidir:**

- **Qué peso le damos, y a cuáles de los dos.** Como referencia, los que ya tienen peso van de 0,20
  (`policy_standing`) a 0,60 (`fraud_history`). El evaluador suma 0,5 por hallazgo y se satura con
  dos.
- **Si antes hay que arreglar un falso positivo conocido.** En un reclamo por daño, `checkAmount`
  compara la factura de compra (lo que vale el equipo) contra el monto reclamado (lo que cuesta la
  reparación), así que siempre salta. Con peso, todos los casos de daño suben de score sin motivo.
  Opción: comparar el importe solo contra el documento que fija el monto (el presupuesto en daño, la
  factura en robo/hurto).
- **Dónde se carga.** Si va en el seed (para toda base nueva) y además en una migración para
  Railway, o si lo carga cada referente desde el panel.

**Qué no cambia aunque se le dé peso:** el score es una señal paralela y no bloquea el Fast Track.
En el carril rápido además se extraen solo los documentos que exige el gate, así que la baja de IMEI
o la última conexión ni se leen (ver la entrada anterior y `mutaciones/constancia-anterior-al-hecho`).

**Qué bloquea:** nada del desarrollo. Mientras no se decida, las mutaciones de datos del set se leen
en `document_analysis` y no en el score.

---

## ~~Fast Track negativo: las salidas por reglas se registran como si las hubiera dado el LLM~~ — ✅ resuelto (29/09/2026)

**Qué se hizo:** la opción 1 de abajo, sin tabla nueva y sin tocar el enum (decisión #6 intacta).

- `cases.was_fast_track` pasó a ser `cases.rules_classification`: qué recomendó el motor cuando
  decidió sin el modelo, nula si decidió el modelo. Las cuatro salidas por reglas (Fast Track,
  exclusión, prescripción, falta de documentación) ya no escriben `llm_analysis`; el porqué queda
  en `rule_result`, que ya tenía la exclusión y ahora suma `PRESCRIPTION` con las dos fechas.
- La falta de documentación no tiene fila propia: la agenda es siempre la exigida y lo que se sube
  después queda con su fecha en `case_document`.
- El DTO lleva `resolvedByRules` hasta el frontend. La card dice "Resultado del motor de reglas" y
  manda a «Evaluación de reglas».
- La decisión del analista sobre una salida por reglas ya no se engancha a un `llm_analysis` de
  una corrida anterior (le pasaba también a Fast Track).
- Reportes: la tasa de Fast Track y la distribución por clasificación dan igual. La coincidencia
  del modelo deja afuera lo que decidió el motor, y "Analizados por el modelo" también: ninguno de
  los dos contaba antes a Fast Track.
- La migración `2026-09-29-quien-decidio-la-clasificacion.sql` corrige los expedientes viejos sin
  adivinar: `FALTA_DOCUMENTACION` no la puede devolver el modelo, y la exclusión y la prescripción
  se reconocen por su motivo, que es un texto fijo del código. Sus filas de `llm_analysis` quedan
  como historial. Va en dos pasos: antes del deploy agrega y llena la columna; después se corre de
  nuevo y `2026-09-29-borrar-was-fast-track.sql` borra la vieja, que el código desplegado todavía usa.

Lo que sigue queda como registro.

**Encontrado:** 22/09/2026, discutiendo si un Fast Track tiene que llegarle al analista como "todo en
regla". No tiene que: Fast Track quiere decir "lo resolvieron las reglas, sin pasar por el LLM", y eso
puede ir a favor o en contra.

**Qué se sabe:** hay cuatro salidas que decide el motor de reglas sin que corra el modelo, y solo la
positiva se llama Fast Track:

| Caso | Se clasifica como | En pantalla dice |
|---|---|---|
| Cumple el gate | `FAST_TRACK` | "Fast Track" |
| Exclusión de cobertura | `LLM_SOLICITA_REVISION_MANUAL` | "Requiere revisión manual" |
| Prescripción (art. 58) | `LLM_NO_RECOMIENDA_APROBAR` | "Recomienda rechazar" |
| Falta documentación | `FALTA_DOCUMENTACION` | "Falta documentación" |

La exclusión y la prescripción son un Fast Track negativo, pero salen con un literal `LLM_*`, así que
se leen como recomendación del modelo.

**Además, la auditoría registra mal a esas salidas.** `ClassificationResultsService` solo se saltea
`llm_analysis` cuando es `FAST_TRACK`. Para la exclusión, la prescripción y la falta de
documentación escribe una fila con `model` = el LLM configurado y la `prompt_version` vigente,
aunque el modelo nunca corrió. El registro que pide la Disposición SSN 2/2023 atribuye al LLM una
recomendación que tomó el motor.

**Choca con la decisión #6 de `CLAUDE.md`:** las 5 categorías están fijadas, `FAST_TRACK` es la
única determinística y las `LLM_*` son recomendaciones del modelo. Por eso no se tocó.

**Qué falta decidir:**

1. **Registrar quién decidió, sin tocar el enum** (la opción recomendada). Generalizar
   `cases.was_fast_track` a algo como `resolved_by = RULES | LLM`, no escribir `llm_analysis`
   cuando decidieron las reglas, y que la UI diga "Por reglas · Recomienda rechazar" en vez de
   presentarlo como del modelo. Arregla la auditoría y deja claro que Fast Track no significa
   "aprobable", sin reabrir la decisión #6.
2. **Que Fast Track sea "resuelto por reglas" con una dirección** (a favor, en contra, a revisión).
   Más prolijo conceptualmente, pero cambia el enum `Classification`, `llm_analysis` (su CHECK
   impide guardar `FAST_TRACK`), los reportes y la decisión #6.

**Relacionado — avisos en cualquier salida por reglas.** Los avisos para el analista no cambian la
dirección del resultado: se suman en cualquiera de estas salidas. El de hecho generador ya existe
(`CLAIM_CAUSE_MATCH`, ver la entrada de arriba). ~~Queda por decidir si las **señales visuales de
adulteración** también avisan~~ — **✅ hecho el 25/09/2026:** aviso `VISUAL_TAMPERING` en cualquier
salida, Fast Track incluido. No bloquea: suma un factor y una fila FAIL en `rule_result`, aparece
en la tarjeta "Para revisar antes de resolver" y el documento lleva el badge "Señales en la imagen".
Sin señales no deja fila, porque un PASS se leería como "el documento es auténtico". Probado por
la UI con la mutación `importe-pegado` (caso #48).

**Qué bloquea:** nada del desarrollo. Mientras no se decida, el analista ve una exclusión o una
prescripción como si las hubiera recomendado el modelo, y la auditoría dice lo mismo.
