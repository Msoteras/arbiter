# Historias de usuario — enhancements pendientes

**Fecha:** 10/08/2026 · **Origen:** lo que quedó abierto al cerrar los 31 defectos del handoff de
pruebas y defectos (el doc se borró el 18/08 al quedar sin nada abierto que no estuviera ya acá).

Cada bloque es **una card de Trello**: el título va en el nombre de la card y el resto en la
descripción. La numeración arranca en H0021 asumiendo que el documento de HU llega hasta H0020 —
confirmar antes de cargarlas.

Ordenadas por lo que más rinde. Las tres primeras se destraban entre sí. **H0025 ya se hizo** — está
tachada abajo, no hay que cargarla.

---

## H0021 · Reglas duras configurables por la aseguradora

**Como** referente de la aseguradora
**quiero** configurar los umbrales de las reglas duras (plazo de denuncia policial, carencia, tope de
eventos, vigencia) desde el panel
**para** ajustar la operación de mi compañía sin depender de un deploy.

**Criterios de aceptación**
- El referente puede ver y editar cada umbral desde el panel de reglas.
- Un cambio de umbral impacta en la próxima clasificación, sin reiniciar servicios.
- Cada regla evaluada deja fila en `rule_result` con su resultado (PASS y FAIL), igual que hoy lo
  hacen las exclusiones de cobertura.
- Una aseguradora sin la regla configurada se comporta como hoy: la regla no se evalúa.

**Por qué importa**
Es la card que desbloquea las otras dos cosas que quedaron a medias:
1. El plazo de la denuncia policial hoy está **hardcodeado en 72 hs** (propiedad
   `arbiter.rules.police-report-deadline-hours`). Contradice la decisión #12 del `CLAUDE.md`.
2. **Ninguna de las reglas duras temporales se audita** (D9/D10/D11/D12/D13): sus umbrales son
   columnas de `coverage`, y `rule_result.rule_id` es FK NOT NULL a `insurer_rule`, así que no tienen
   a qué apuntar. Sin esto, la auditoría de la Disposición SSN 2/2023 cubre las exclusiones de
   cobertura y nada más.

**Notas técnicas**
Modelarlas como filas de `insurer_rule` con `rule_type` propio y configuración en el JSONB, igual que
`COVERAGE_EXCLUSION`. Las columnas de `coverage` pasan a ser
el default cuando no hay regla, o se migran.

---

## H0022 · Extraer del relato del asegurado los datos que las reglas necesitan

**Como** analista de siniestros
**quiero** que el sistema detecte en la descripción del siniestro los hechos que definen la cobertura
**para** que un caso no cubierto no llegue a mi bandeja como si lo estuviera.

**Criterios de aceptación**
- La descripción que escribe el asegurado en el alta pasa por una extracción que devuelve **datos
  tipados**, no prosa.
- Si el relato indica que el damnificado es un familiar y la cobertura no alcanza al grupo familiar,
  el caso se deriva a revisión manual con el motivo a la vista.
- Ante la duda, el dato sale como `DESCONOCIDO` y la regla **no** se evalúa: la ambigüedad nunca
  cuenta en contra del asegurado.
- El modelo solo extrae; la decisión de cobertura la toma el código.

**Por qué importa**
`covers_family_group` quedó implementado **solo sobre los documentos adjuntos**. Si el asegurado
escribe "le robaron el celular a mi hijo" en la descripción y ningún documento lo menciona, la regla
no se entera. Es la mitad que falta de D9.

**Notas técnicas**
Misma forma que `OllamaDocumentAnalyzer` → `DocumentExtraction.Fields`, pero sobre `claim.description`.
Corre antes de la clasificación. Reusa `CoverageScopeEvaluator`, que ya evalúa la regla.

---

## H0023 · Cruzar el IMEI del documento contra el del equipo asegurado

**Como** analista de siniestros
**quiero** ver marcado cuando el IMEI de un documento no coincide con el del equipo asegurado
**para** detectar constancias fabricadas o de otro equipo.

**Criterios de aceptación**
- El bien asegurado tiene IMEI propio en el modelo de Arbiter, sincronizado desde la BD Aseguradora
  (hoy solo existe del lado de la aseguradora).
- El analista ve el IMEI del equipo en la ficha del expediente.
- La discrepancia aparece como factor del score de fraude con el detalle de ambos valores.

**Por qué importa**
El cruce ya funciona (D4b), pero el IMEI vive **solo en `aseguradora_*.poliza.imei`**: no está en la
`policy` local ni se muestra en ninguna pantalla. El analista ve el factor pero no puede verificarlo.

---

## H0024 · Ver los datos de la póliza sobre los que se clasificó

**Como** analista de siniestros
**quiero** ver la foto de la póliza tal como estaba cuando se clasificó el expediente
**para** entender por qué el sistema puntuó lo que puntuó, aunque los datos hayan cambiado después.

**Criterios de aceptación**
- El detalle del expediente muestra: suma asegurada, estado de pago, vigencia y cantidad de
  siniestros previos **al momento de clasificar**, con la fecha de esa consulta.
- Se distingue de los datos actuales de la póliza cuando difieren.

**Por qué importa**
La foto ya se guarda (`policy_snapshot`, D27) y **no la lee nadie**. Es lo que hace auditable y
reproducible una clasificación ante la Disposición SSN 2/2023 — pero solo si alguien puede verla.

---

## ~~H0025 · Configurar las condiciones de la cobertura desde el panel~~ — ✅ HECHA (10/08)

> Se implementó el mismo día: los tres campos están en la solapa Coberturas, se persisten por
> `CoverageUpsertRequest` y el motor evalúa lo guardado. **No cargar esta card.** Se deja el texto
> como registro de por qué existía.

**Como** referente de la aseguradora
**quiero** editar la carencia, si la cobertura alcanza al grupo familiar y si un siniestro la agota
**para** que la configuración refleje lo que dice la póliza que vendemos.

**Criterios de aceptación**
- Los tres campos son visibles y editables en la solapa **Coberturas**, junto a los que ya están.
- El motor evalúa lo que quedó guardado.
- Se aclara en pantalla qué significa cada uno (la carencia sobre todo).

**Por qué importa**
El motor ya evalúa los tres (D9), pero el referente **no puede verlos ni cambiarlos**: se configuran
por SQL. La pantalla ya existe, es sumarle campos.

---

## H0026 · Avisarle al asegurado cuando cambia el estado de su expediente

**Como** asegurado
**quiero** recibir un mail cuando mi expediente cambia de estado
**para** enterarme sin tener que entrar a mirar.

**Criterios de aceptación**
- Cada transición de estado dispara una notificación al mail del asegurado.
- El mail dice el estado nuevo en lenguaje del asegurado, **sin** clasificación, score ni motivos
  internos.
- Queda registro de la notificación enviada.
- Un fallo de envío no rompe la transición del expediente.

**Por qué importa**
Es la decisión #9 del `CLAUDE.md`. `Notification` tiene entidad y repository con **cero escritores**;
SendGrid hoy solo se usa para invitar usuarios en auth-service.

---

## H0027 · Alertar el vencimiento del plazo legal de respuesta

**Como** analista de siniestros
**quiero** que el sistema me avise antes de que venza el plazo legal para expedirme
**para** no dejar vencer un expediente por olvido.

**Criterios de aceptación**
- El plazo arranca **cuando la documentación está completa**, no cuando se registra la denuncia.
- La bandeja muestra los expedientes próximos a vencer, y los vencidos.
- El plazo es configurable, no una constante de código.

**Por qué importa**
Doble problema. `response_deadline` se calcula al crear el expediente y **nunca se vuelve a leer** —
nada avisa cuando vence, y el silencio de la aseguradora equivale a aceptación. Y arranca el reloj en
el momento equivocado: el paper dice desde la documentación completa (ver
`gap-historias-usuario.md` §1), así que hoy adelanta el vencimiento y la alerta avisaría mal.

---

## H0028 · Reportes y tablero del referente

**Como** referente de la aseguradora
**quiero** ver métricas de los expedientes de mi compañía
**para** entender cómo viene operando y justificar cambios de configuración.

**Criterios de aceptación**
- Volumen de siniestros por período, ramo y estado.
- Distribución de clasificaciones y de bandas de riesgo.
- Tasa de Fast Track y tiempo promedio hasta la decisión del analista.
- Los datos son de la aseguradora del referente, nunca de otra.

**Por qué importa**
`reports-service` tiene solo la entidad `Metric` y su repository: **sin controllers, sin services**.
Las dos pantallas dicen "TODO MOCK" en su propio encabezado.

---

## H0030 · Reintentos de clasificación más robustos ante una caída transitoria

**Como** analista de siniestros
**quiero** que una clasificación que falló por una caída momentánea de un servicio dependiente se
reintente sola, sin que dependa de que alguien note el error y aprete "Reintentar clasificación" a
mano
**para** no tener expedientes trabados en `PENDING_CLASSIFICATION` (o vencidos a
`CLASSIFICATION_FAILED`) por un blip de infraestructura que ya se resolvió.

**Criterios de aceptación**
- Los reintentos de `processClaimClassification` (`ClaimClassificationService`, hoy 3 intentos con
  backoff 2s/4s) quedan espaciados en una ventana bastante más ancha que ~14 segundos — un
  contenedor reiniciándose o un deploy tarda más que eso, y hoy los tres intentos se queman antes
  de que la dependencia vuelva a estar arriba.
- Al arrancar `cases-service` (o `classification-service`, según dónde termine viviendo la lógica),
  el sistema barre los expedientes fallidos **por un motivo de infraestructura** (timeout, conexión
  rechazada, 5xx) y los reencola automáticamente — sin esperar a que un analista entre y los
  reintente a mano.
- Se distingue "falló porque el servicio no respondía" de "falló por otra cosa" — no tiene sentido
  reintentar solo algo que nunca va a resolver distinto (ver nota técnica).

**Por qué importa**
Encontrado el 17/08 probando con `rules-service` caído: `ClaimClassificationService.
processClaimClassification` agota sus 3 reintentos en ~14 segundos y tira la excepción — no
reintenta más. `ClassificationRefreshScheduler` (cases-service), que corre cada 5 segundos, **no
vuelve a disparar la clasificación**, solo pregunta si ya hay un resultado; como nunca lo va a
haber, el expediente suma intentos en el vacío durante `maxAttempts` × `interval-ms` (~10 minutos
con los defaults) hasta que recién ahí pasa a `CLASSIFICATION_FAILED`. Arreglar la caída del lado de
infra en el medio no cambia nada: el expediente sigue trabado hasta que alguien lo note y aprete
"Reintentar clasificación" (el botón que ya existe, ver ítem 22 de `frontend-bugs-ux.md`).

**Notas técnicas**
- Separar, en `processClaimClassification`, las excepciones que valen la pena reintentar solas
  (`ResourceAccessException`, `HttpServerErrorException` — infraestructura) de las que no (un 4xx de
  negocio, por ejemplo). El `@Retryable` actual ya filtra por tipo de excepción
  (`retryFor = {HttpServerErrorException.class, ResourceAccessException.class}`); lo que falta es
  correr esos reintentos en una ventana más ancha, no solo agrandar el backoff dentro del mismo
  intento de request.
- El barrido de arranque necesita alguna forma de distinguir "falló por infraestructura" de "falló
  por otra razón" para no reintentar indefinidamente algo que nunca va a cambiar — hoy
  `CLASSIFICATION_FAILED` no guarda el motivo del último fallo en ningún lado consultable por este
  barrido.

---

## ~~H0031 · Persistir y mostrar los datos que el modelo extrajo de cada documento~~ — ✅ HECHA (18/08)

> **Implementada el 18/08.** Las tablas son `document_analysis` + `document_visual_finding` (no
> `document_extraction` como decía la propuesta: `DocumentExtraction` ya es el nombre del DTO
> transitorio, y `*_analysis` es la familia que ya usan `llm_analysis` / `risk_analysis` /
> `image_analysis`). **Falta sumarlas al DER** (`docs/arbiter der.mdj`, StarUML, a mano) — el
> esquema y el código ya están, el diagrama es lo único que quedó atrás. Columnas exactas en
> `db/migrations/2026-08-18-datos-extraidos.sql`.
>
> **No cargar esta card.** Se deja el texto como registro de por qué existía.

**Como** analista de siniestros
**quiero** ver, por cada documento adjunto, los datos que el modelo leyó (fecha, importe, ítem,
IMEI, a quién le pasó) y si coinciden con la póliza
**para** entender de un vistazo qué dijo cada papel sin tener que abrirlo y compararlo a mano.

**Criterios de aceptación**
- El detalle del expediente tiene una solapa ("Datos extraídos") con, por documento adjunto, los
  campos tipados que el modelo extrajo y las señales visuales que encontró (si hubo alguna).
- Cuando un campo del documento no coincide con el dato equivalente de la póliza/bien asegurado
  (ej. IMEI), queda marcado como discrepancia, no como un valor más de la lista.
- Un documento sin ese campo (ej. una foto del bien no tiene importe) se muestra como "no
  aplica", nunca como una discrepancia — un campo ausente no es lo mismo que un campo que no
  matchea (mismo criterio que ya usa `DocumentInconsistencyEvaluator` con `null`).
- El dato queda persistido y auditable: no se recalcula ni desaparece si se vuelve a mirar el
  expediente más tarde.

**Por qué importa**
`classification-service` ya hace esta extracción — `OllamaDocumentAnalyzer` produce un
`DocumentExtraction` (transcripción + `Fields` tipados: `documentDate`, `amount`,
`itemDescription`, `imei`, `affectedParty` + `visualFindings`) por cada documento, y
`ClassificationOrchestrator` ya lo usa para armar el prompt y alimentar
`DocumentInconsistencyEvaluator` (el factor de riesgo que compara IMEI). El dato existe y ya se
calculó — hoy se descarta apenas termina esa corrida, así que el analista nunca lo ve, solo el
resultado indirecto (un factor de riesgo con el nombre del campo, sin el valor). La solapa "Datos
extraídos" del detalle del expediente es un placeholder vacío esperando exactamente esto.

Relacionada con H0023 (cruce de IMEI): H0023 es más chica y depende de que el IMEI del bien
asegurado exista del lado de Arbiter; esta historia es la pieza que falta para que el analista
vea el IMEI *del documento* en primer lugar, más el resto de los campos.

**Notas técnicas**
1. Entidad nueva en `classification-service` (algo como `document_extraction`, una fila por
   documento adjunto) que persista lo que hoy vive solo en memoria dentro de
   `ClassificationOrchestrator.resolveClassification`/`extractAllAttachments` — mismo espíritu que
   `ClassificationLog`, es dato de auditoría del análisis.
2. Endpoint REST interno para que `cases-service` lo consulte (ej.
   `GET /claims/{id}/document-extractions`), siguiendo el mismo patrón que ya usa para traer la
   clasificación.
3. Sumar el campo a `CaseResponse`/`ExpedienteResponse` (`List<DocumentExtractionSummary>`, con
   tipo de documento, campos y si matcheó o no contra la póliza).
4. Frontend: ya está lista la solapa "Datos extraídos" del lado del componente
   (`expediente-detail.component.ts`/`.html`) — hoy oculta de `tabs()` porque no hay nada que
   mostrar. Con el endpoint arriba, solo falta el `computed` que la vuelva a sumar a la lista
   cuando `analysisReasons`-style haya datos, y el bloque de template que los liste (ver el
   `@case ('datos')` que ya existe como stub, y el patrón de la solapa "Razones" recién agregada
   como referencia de cómo se gatea una tab por presencia de datos).

---

## H0032 · Mostrar la traza de reglas duras que ya se audita y nadie lee

**Prioridad alta — más barata que H0031 y toca directo la auditoría de la Disposición SSN
2/2023.** No hace falta tocar el DER ni crear ninguna tabla: `rule_result` (`resultado_regla`)
existe, está en el DER, y **ya se escribe** en cada clasificación — el gap es solo que nadie la
lee de vuelta.

**Como** analista de siniestros
**quiero** ver, por expediente, cada regla dura que se evaluó (exclusión de cobertura, plazos,
carencia, tope de eventos) con su resultado — no solo las que bloquearon algo
**para** auditar la clasificación completa, no solo el motivo que terminó importando.

**Criterios de aceptación**
- El detalle del expediente muestra una lista de reglas evaluadas: tipo de regla, resultado
  (PASS/FAIL) y el valor evaluado (ej. `"claimCause=Hurto (id=3)"`).
- Se ven **las que pasaron, no solo las que fallaron** — hoy el analista solo se entera de las que
  bloquean algo (porque esas sí llegan como texto a "Razones"); las que se evaluaron y no
  encontraron nada quedan invisibles, y son justamente la prueba de que se revisó todo, no solo lo
  que saltó.
- Un expediente sin ninguna regla dura evaluada (ej. resuelto por LLM sin reglas de por medio) no
  muestra nada — mismo criterio que "Razones"/"Peritaje": sin datos, no se ocupa espacio.

**Por qué importa**
`ClassificationResultsService.saveRuleResults` (`classification-service`) escribe una fila en
`rule_result` por cada regla — exclusión de cobertura, D9-D13 — **con ambos resultados**, PASS y
FAIL, exactamente porque la Disposición SSN 2/2023 pide "qué regla se evaluó y con qué resultado",
no solo los rechazos. Ese trabajo ya está hecho y ya cumple la norma del lado de la escritura. Pero
la tab "Razones" (esta misma sesión) solo muestra el subconjunto que ya venía como prosa en
`llm_reason` — que es **estrictamente más chico**: `TemporalRuleEvaluator` arma `reasons` (texto,
solo lo que bloquea) y `findings` (una fila por regla evaluada, `rule_result`) como dos listas
separadas, y solo la primera llega a pantalla. El registro completo, el que de verdad prueba que
se auditó todo, se escribe y se descarta sin que nadie lo mire — es auditoría de papel, no de
pantalla.

**Notas técnicas**
1. `RuleResultRepository` no tiene ningún método de consulta (`extends JpaRepository` a secas) —
   agregar `findByCaseId(Long caseId)` o similar.
2. Endpoint REST interno en `classification-service` (ej. `GET /claims/{id}/rule-results`), mismo
   patrón que el resto.
3. Sumar a `CaseResponse`/`ExpedienteResponse` (`List<RuleResultSummary>`: tipo de regla, resultado,
   valor evaluado — sin exponer `ruleId` crudo, alcanza con el tipo para que el analista entienda
   qué se evaluó).
4. Frontend: puede vivir dentro de la tab "Razones" ya existente (como una sección aparte,
   "Trazabilidad completa" o similar, debajo de los motivos en prosa) en vez de una tab nueva —
   son la misma pregunta del analista ("por qué se clasificó así"), solo con dos niveles de
   detalle. Evaluar con Fede si conviene separarlo.
5. De paso: `RuleResult.java` tenía un javadoc que decía *"no rule engine evaluates anything into
   this table yet"* — ya no es cierto (`ClassificationResultsService` sí escribe), se corrigió el
   comentario en esta misma sesión para que no vuelva a leerse como "tabla sin implementar".

**Extra, menor, no amerita card propia:** `LlmAnalysis` también persiste `model`, `promptVersion`,
`latencyMs` y `analyzedAt` (`ClassificationResultsService.saveResult`) y `getStatus`/`ClaimResponse`
no los devuelve — se pierden en el camino igual que `rule_result`, pero es metadata más de
ingeniería/soporte (qué modelo corrió, cuánto tardó) que de decisión del analista. Si en algún
momento se arma un panel de diagnóstico/soporte para ver clasificaciones lentas o con modelo
distinto al esperado (relevante después de los bugs de este mismo handoff — ver
`docs/handoff-ollama-cpu-y-scheduler.md`), es la misma extensión de `ClaimResponse`/`CaseResponse`
que pide H0032, sumando estos 4 campos.

---

# Decisiones de negocio (no son historias)

No se pueden estimar hasta que alguien del equipo las responda. Van como cards de decisión o se
resuelven en una reunión.

**1 · ¿Qué son `fraud_determined` y `destination`?**
Dos columnas de `cases` que están en el DER y no escribe nadie. Hoy el analista solo aprueba o
rechaza. `destination` no tiene valores definidos en ninguna fuente — el DER la dibuja como un
`VARCHAR(40)` suelto. ¿Son salidas reales del proceso (pago / rechazo / derivación a investigación) o
quedaron del modelo? Hasta que se conteste no se les inventa semántica.

**2 · ¿`is_individual` es la negación de `covers_family_group`?**
El seed es consistente con esa lectura (`covers_family_group=FALSE` / `is_individual=TRUE` en las
dos coberturas). Si lo es, hay que **dropearla del DER o derivarla**, no dejar las dos vivas: es el
mismo hecho guardado dos veces.

**3 · ¿Modelamos pólizas colectivas?**
`Tomador` y `N° de certificado` se sacaron de la ficha del expediente porque en nuestro modelo cada
póliza es individual y el certificado es 1:1 con ella. Vuelven las dos juntas si algún día se modela
la póliza colectiva — que es como BBVA vende de verdad, a través del banco.
