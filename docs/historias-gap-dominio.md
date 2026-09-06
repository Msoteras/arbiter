# Historias de usuario — lo que quedaba abierto del procedimiento real de BBVA

**Origen:** `docs/gap-dominio-bbva.md`, la comparación entre Arbiter y `Siniestros_NSIN001` (el
procedimiento interno de gestión de siniestros de BBVA Seguros, recibido el 31/08/2026). Ese
documento se borró el 06/09/2026: de sus 21 puntos, 17 quedaron resueltos entre el 31/08 y el
02/09, y los dos que seguían siendo trabajo de desarrollo son estas dos cards.

Los otros dos abiertos **no eran historias** y se mudaron a `temas-a-discutir.md`: las reservas
(SPL) y el desempate cuando dos coberturas responden al mismo hecho generador.

Cada bloque es **una card de Trello**: el título va en el nombre de la card y el resto en la
descripción. La numeración sigue desde H0039, el último del backlog anterior. **Este archivo se
borra una vez cargadas las dos cards.**

---

## H0040 · El expediente no tiene cómo terminar salvo aprobado o rechazado

**Como** analista de siniestros
**quiero** poder cerrar un expediente por el motivo real que lo terminó
**para** que la bandeja y los reportes digan qué pasó de verdad, en vez de amontonar todo bajo
"rechazado".

**Criterios de aceptación**
- El analista puede cerrar un expediente como **desistido** (el asegurado abandonó el reclamo) y
  como **baja por error** (la denuncia nunca debió existir: duplicada, cargada sobre la póliza
  equivocada), cada uno con motivo obligatorio.
- La **prescripción** deja de resolverse por el `REJECTED` genérico: un expediente prescripto se
  distingue de uno rechazado por el fondo del asunto, tanto en la bandeja como en el detalle.
- Los estados nuevos son **terminales**: no admiten eventos sin pasar antes por la reapertura que
  ya existe (H0034 / punto 11 del doc de gaps).
- Cada estado nuevo tiene su fila en `arbiter_common.case_status`, su label en español en el
  frontend, y su tono en `estadoTone()` — igual que `LAPSED` cuando se sumó el 31/08.
- La bandeja los filtra como cerrados, no como en curso, y los reportes del referente los cuentan
  por separado.
- El asegurado ve el estado simplificado que le corresponde, sin motivos internos.

**Por qué importa**
`CaseStatus` tiene hoy 8 valores (`PENDING_CLASSIFICATION`, `PENDING_ANALYST_REVIEW`,
`CLASSIFICATION_FAILED`, `AWAITING_DOCUMENTATION`, `PENDING_EXPERT_REPORT`, `APPROVED`, `REJECTED`,
`LAPSED`) contra los 14+ del proceso real. La falta no es cosmética: la invariante que el doc fuente
cita como central —*"los terminales no admiten eventos sin pasar por rehabilitación"*— no tiene
dónde apoyarse mientras "terminal" signifique solo aprobado o rechazado. Un desistimiento y una
denuncia cargada por error hoy terminan indistinguibles de un rechazo por fondo, y eso ensucia el
único número que el referente mira: cuántos se rechazan y por qué.

La prescripción es el caso más visible: el gate determinístico ya existe y funciona (art. 58 Ley
17.418, con su test en `ClassificationOrchestratorPrescriptionTest`), pero desemboca en `REJECTED`
como cualquier otro rechazo. La información de que prescribió está en los factores, no en el estado.

**Fuera de alcance** (deliberado, y conviene que quede escrito): `EN MEDIACION/JUICIO`,
`AUTORIZADO A LIQUIDAR` y `LIQUIDADO`. Los tres son estados de frontera que el doc pide que Arbiter
*conozca* aunque no ejecute, pero dependen de integraciones con el sistema de la compañía que no
existen. `DENUNCIA DE HECHO (RC)` tampoco entra: depende de la decisión de reservas que sigue
abierta en `temas-a-discutir.md`.

**Notas técnicas**
Las transiciones viven en `CaseStatusService.VALID_TRANSITIONS`, un `Map` hardcodeado — es
deliberado y está decidido (transiciones fijas, no configurables por aseguradora, 26/08), así que
esta card se implementa ahí y **no** abre el tema de moverlas a BD. Ojo con
`DeadlineSweepScheduler` y `CaseSpecifications`: los estados terminales nuevos tienen que quedar
excluidos de la alerta de vencimiento y de la lente "Por vencer", como ya lo están `APPROVED`,
`REJECTED` y `LAPSED`. El seed de `case_status` está en `db/init-multitenant.sql` y necesita
migración para la base ya desplegada, igual que la de `LAPSED`
(`db/migrations/2026-08-31-caducidad.sql` sirve de molde).

---

## H0041 · Un siniestro que se resuelve reparando el equipo no tiene a dónde ir

**Como** analista de siniestros
**quiero** derivar el expediente a un servicio técnico o a una cristalería
**para** gestionar la reparación con el proveedor sin sacar el caso del sistema.

**Criterios de aceptación**
- El analista puede derivar un expediente a un proveedor de **servicio técnico** o de
  **cristalería**, con motivo, igual que hoy deriva a un perito.
- El referente administra el catálogo de esos proveedores por zona y ramo, en el mismo panel donde
  ya administra los estudios liquidadores.
- El proveedor recibe la notificación y su devolución vuelve al analista **sin resolver el
  expediente por su cuenta** (decisión de arquitectura #5: el analista decide siempre).
- La derivación queda en la trazabilidad del expediente: quién derivó, a quién, cuándo y por qué.
- Un expediente derivado a reparación no cuenta como vencido mientras espera al proveedor — mismo
  tratamiento que `PENDING_EXPERT_REPORT`.

**Por qué importa**
El enum `Classification` responde *"¿lo apruebo?"* — es la recomendación del LLM al analista. La
tipología del proceso real (§7 del doc fuente: Exprés, Doc. reducida, Doc. amplia, Servicio técnico,
Cristalería, Urgente/estudio liquidador) responde otra pregunta: *"¿qué trámite sigue?"*. Son dos
ejes distintos y hoy están mezclados en uno solo.

De esa tipología ya está cubierto casi todo: `FAST_TRACK` cubre el caso Exprés, y la derivación a
estudio liquidador está bien resuelta por `ExpertAssessment` + `ExpertFirm` (catálogo por
zona/ramo que administra el referente, derivación con motivo, notificación, veredicto que vuelve al
analista). Lo que no tiene lugar es la derivación a **reparación**: ramas 22/23/24 del doc, cero
resultados en código de producción. Es conceptualmente distinta del peritaje —no investiga causa,
gestiona reparación o reposición con un proveedor— y es la salida natural de los hechos generadores
de daño y rotura, que son justamente los que el ramo Celulares acaba de incorporar.

**Notas técnicas**
Dos caminos, y conviene cerrar cuál antes de estimar:

- **(a) Generalizar `ExpertFirm`/`ExpertAssessment`** con un tipo de proveedor
  (`ESTUDIO_LIQUIDADOR` / `SERVICIO_TECNICO` / `CRISTALERIA`), reusando catálogo, derivación y
  notificación. Es la que menos código nuevo pide y la que mantiene una sola forma de derivar.
- **(b) Revivir `cases.destination`** con esos tres valores como enum cerrado. **Desaconsejada:**
  `der-gaps.md` ya tiene esa columna registrada como candidata a dropear —no la lee ni la escribe
  nadie, y la funcionalidad que iba a cubrir la resolvieron `fraud_determined` y `ExpertAssessment`
  por otro lado— así que reusarla contradice una decisión ya anotada.

Si sale (a), ojo con el naming: `ExpertFirm` deja de describir lo que la tabla contiene.
