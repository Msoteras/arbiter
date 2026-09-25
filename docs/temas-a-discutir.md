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
- **Celulares → crear** la cobertura *Daño accidental*, sin excluir *Rotura accidental* ni *Caída*.
  ~~Editar sus exclusiones, que ya existe~~: verificado en Railway el 25/09/2026, Provincia tiene en
  Celulares solo *Robo de celular* y *Hurto*. La BD Aseguradora no modela esos dos hechos como
  coberturas separadas: los responde *Daño accidental*.

**Estado al 25/09/2026: sin hacer.** En Railway, Tecnología Portátil sigue solo con *Daño
accidental* y Celulares sin él.

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

## La franquicia en una reparación deja casi todo en cero

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
- `purchase_to_report_time` sigue sin peso.

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

## Fast Track negativo: las salidas por reglas se registran como si las hubiera dado el LLM

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
