# Historias de usuario — enhancements pendientes

**Fecha:** 10/08/2026 · **Origen:** lo que quedó abierto al cerrar los 31 defectos del handoff de
pruebas y defectos (el doc se borró el 18/08 al quedar sin nada abierto que no estuviera ya acá).

Cada bloque es **una card de Trello**: el título va en el nombre de la card y el resto en la
descripción. La numeración arranca en H0021 asumiendo que el documento de HU llega hasta H0020 —
confirmar antes de cargarlas.

Ordenadas por lo que más rinde. Las tres primeras se destraban entre sí. **H0025 ya se hizo** — está
tachada abajo, no hay que cargarla.

---

## ~~H0021 · Reglas duras configurables por la aseguradora~~ — ✅ HECHA

> Confirmada implementada al planificar el sprint 9 (26/08). `HardRuleController`/`HardRuleDto`
> (por cobertura: carencia, plazo de denuncia, plazo de denuncia policial, tope de eventos) +
> `InsurerHardRuleController`/`InsurerHardRuleDto` (de toda la aseguradora: vigencia, mora) cubren
> los cuatro umbrales que pedía la historia, con `PUT` desde el panel del referente y snapshot en
> el historial. El plazo de denuncia policial —el que estaba hardcodeado en 72h— ya no lo está: el
> propio javadoc de `HardRuleController` lo dice ("until now the police-report deadline was a fixed
> 72h property for every company, against decision #12"). Auditoría también cerrada:
> `TemporalRuleEvaluator` escribe `RuleFinding` PASS/FAIL a `rule_result` contra filas reales de
> `insurer_rule`, ya no contra columnas sueltas de `coverage` sin nada que apuntar. **No cargar
> esta card.** Se deja el texto como registro de por qué existía.

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

## ~~H0026 · Avisarle al asegurado cuando cambia el estado de su expediente~~ — ✅ HECHA (25/08)

> Es H0016 (`gap-historias-usuario.md` §2), que se cerró el 25/08 mientras este doc seguía
> describiendo el gap de antes: `CaseNotificationService` (cases-service) ya manda mail y aviso en
> el panel en los cuatro momentos — denuncia recibida, documentación requerida, aprobado, rechazado
> — más `ExpertNotificationService` para la derivación a perito. **No cargar esta card**, ya está
> hecha. Se deja el texto como registro de por qué existía.

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

> **Es H0017**, no una card nueva — el análisis completo (por qué el reloj arranca mal, el orden de
> implementación, la dependencia con el modelo de plazos) vive en `gap-historias-usuario.md` §1 y
> §2. Lo de acá abajo es la misma historia en formato de card; cargar **una sola**, con este texto
> como descripción y el link al gap doc para el detalle técnico.

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

> **Reverificado al planificar el sprint 9 (26/08).** Sigue abierta — nada de lo que describe el
> reintento automático cambió. Lo que sí existía **antes** de esta historia, y no hay que
> confundir con lo que falta: el botón manual "Reintentar clasificación"
> (`POST /{id}/retry-classification`, `CaseServiceImpl.retryClassification`) — eso nunca fue el
> gap. Lo que sigue faltando, confirmado línea por línea:
> - `ClaimClassificationService`: `@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000,
>   multiplier = 2.0))` en los dos métodos — sigue siendo la ventana de ~14s.
> - `ClassificationRefreshScheduler`: al agotarse los reintentos solo escribe un string en el
>   motivo de la transición (`"clasificación fallida tras N reintentos"`) — no hay ninguna columna
>   consultable que distinga infraestructura de negocio, y no hay ningún barrido que reencole
>   `CLASSIFICATION_FAILED` al arrancar.

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
"Reintentar clasificación" (el botón que ya existe).

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

## H0032 · Trazabilidad completa del expediente: reglas evaluadas + relación del asegurado con la compañía

> **Ampliada al planificar el sprint 9 (26/08).** Empezó como "mostrar `rule_result`" nada más;
> se le sumó una segunda mitad — la relación del asegurado con la aseguradora (pólizas, mora,
> historial) — porque comparte el mismo diagnóstico: el dato ya existe, ya se calculó, y hoy nadie
> lo lee. Las dos mitades pueden convivir en la misma solapa nueva del detalle del expediente.

**Prioridad alta** — más barata que H0031 y toca directo la auditoría de la Disposición SSN
2/2023. Ninguna de las dos mitades necesita tabla nueva ni tocar el DER: todo el dato ya se
persiste, el gap es pura lectura.

**Como** analista de siniestros
**quiero** ver, en una sola solapa del expediente, tanto cada regla dura que se evaluó (con su
resultado, no solo las que bloquearon algo) como toda la relación del asegurado con la
aseguradora — sus pólizas, si está al día, cuántos siniestros tuvo antes y si tiene antecedentes
**para** auditar la clasificación completa y entender el contexto del asegurado sin cruzar datos a
mano ni pedirlos aparte.

**Criterios de aceptación**

*Reglas evaluadas:*
- Lista de reglas evaluadas: tipo, resultado (PASS/FAIL), valor evaluado (ej.
  `"claimCause=Hurto (id=3)"`).
- Se ven las que **pasaron**, no solo las que fallaron — hoy solo llegan a pantalla las que
  bloquean algo (como texto en "Razones"); las que se evaluaron sin encontrar nada quedan
  invisibles, y son justamente la prueba de que se revisó todo.
- Sin reglas evaluadas (ej. resuelto por LLM sin reglas de por medio), no se muestra nada — mismo
  criterio que "Razones"/"Peritaje".

*Relación del asegurado con la compañía:*
- Estado de pago de la póliza **al momento en que se clasificó** (al día / en mora), con la fecha
  de esa consulta — no el estado actual si cambió después.
- Todas las pólizas activas del asegurado en esta aseguradora, no solo la de este siniestro.
- Cantidad de siniestros previos y monto total reclamado históricamente.
- Antecedentes de fraude declarados, si los hay, con quién los declaró y cuándo.
- Todo lee de lo que ya está persistido — nada se recalcula ni se pide en vivo a la aseguradora al
  abrir la pantalla.

**Por qué importa**
Las dos mitades comparten el mismo problema: el dato ya existe y ya se calculó, y hoy se descarta
o queda enterrado.
- `rule_result` se escribe completo (PASS y FAIL) por `ClassificationResultsService.saveRuleResults`
  — la exclusión de cobertura y D9-D13, exactamente porque la Disposición SSN 2/2023 pide "qué
  regla se evaluó y con qué resultado", no solo los rechazos. La tab "Razones" solo muestra el
  subconjunto que ya venía como prosa en `llm_reason`: `TemporalRuleEvaluator` arma `reasons`
  (texto, solo lo que bloquea) y `findings` (una fila por regla, `rule_result`) como dos listas
  separadas, y solo la primera llega a pantalla. Es auditoría de papel, no de pantalla.
- `policy_snapshot` (D27) congela suma asegurada, estado de pago y siniestros previos al momento
  de clasificar — y no lo lee nadie.
- El resto de las pólizas del asegurado: hoy **solo el asegurado y el referente** pueden verlas
  (`PolicyController`, `@PreAuthorize hasAnyRole('ASEGURADO', 'REFERENTE_ASEGURADORA')`) — el
  analista, que es quien más las necesita para decidir, no tiene acceso. El servicio que las trae
  (`PolicyService.listByInsured`) ya existe y es reusable; falta el camino para el analista.
- Antecedentes de fraude (`InsuredFraudRecord`) existen como entidad pero no aparecen en el
  detalle del expediente, solo en la configuración del referente.

**Notas técnicas**
1. `RuleResultRepository` no tiene ningún método de consulta (`extends JpaRepository` a secas) —
   agregar `findByCaseId(Long caseId)`.
2. Endpoint interno en `classification-service` (ej. `GET /claims/{id}/rule-results`), mismo
   patrón que el resto de lo interno.
3. Endpoint nuevo en `cases-service` para "pólizas del asegurado de este expediente" — reusa
   `PolicyService.listByInsured`, pero `@PreAuthorize` para `ANALISTA_SINIESTROS` scoped al
   `insuredId` **de ese caso puntual**, no una consulta libre por cualquier DNI.
4. Sumar a `CaseResponse`/`ExpedienteResponse`: `List<RuleResultSummary>` (tipo, resultado, valor
   evaluado — sin exponer `ruleId` crudo), el `policy_snapshot` del caso, `List<PolicyResponse>` de
   las otras pólizas, y los antecedentes de fraude si existen.
5. Frontend: solapa nueva en `expediente-detail.component` (o secciones dentro de "Razones" si al
   final se decide no separarla — evaluar con el equipo). Reusa el patrón de gateo por presencia
   de datos que ya usan "Razones"/"Peritaje"/"Datos extraídos": sin datos, la tab no aparece.
5. De paso: `RuleResult.java` tenía un javadoc que decía *"no rule engine evaluates anything into
   this table yet"* — ya no es cierto (`ClassificationResultsService` sí escribe), se corrigió el
   comentario en esta misma sesión para que no vuelva a leerse como "tabla sin implementar".

**Extra, menor, no amerita card propia:** `LlmAnalysis` también persiste `model`, `promptVersion`,
`latencyMs` y `analyzedAt` (`ClassificationResultsService.saveResult`) y `getStatus`/`ClaimResponse`
no los devuelve — se pierden en el camino igual que `rule_result`, pero es metadata más de
ingeniería/soporte (qué modelo corrió, cuánto tardó) que de decisión del analista. Si en algún
momento se arma un panel de diagnóstico/soporte para ver clasificaciones lentas o con modelo
distinto al esperado), es la misma extensión de `ClaimResponse`/`CaseResponse`
que pide H0032, sumando estos 4 campos.

---

## ~~H0033 · Conversación entre el asegurado y el analista~~ — ✅ HECHA (30/08)

> Es la card [#145](https://trello.com/c/53Bvc7BO) ("Resolver Comunicacion con Asegurado"), cuya
> descripción entera era *"mail o chat"*. Las tres decisiones que la bloqueaban —canal,
> direccionalidad y alcance— las cerró el equipo el 30/08 y con eso se implementó; la sección
> "Conversación con el asegurado" de `temas-a-discutir.md` se borró al quedar resuelta.

**Como** analista de siniestros **quiero** pedirle una aclaración o un documento puntual al
asegurado desde el expediente, y que me pueda contestar ahí mismo, **para** no tener que salir del
sistema —ni perder el rastro de lo que se pidió— cada vez que falta algo.

**Como** asegurado **quiero** poder preguntar sobre mi siniestro y responder lo que me pidan
**para** no quedarme sin saber cómo comunicarme.

**Criterios de aceptación**

- Un hilo por expediente, cronológico, con los dos lados. Cada mensaje dice quién lo escribió y
  cuándo, y si la otra parte ya lo leyó.
- El analista lo ve como una solapa más del expediente, con un punto cuando hay algo sin leer.
- El asegurado lo ve en el seguimiento de su siniestro.
- El referente **lee** el hilo y no escribe, igual que ve la bandeja.
- Nadie ve el hilo de un expediente ajeno: pedirlo devuelve 404, no 403.
- Cada mensaje nuevo avisa a la otra parte. Al asegurado por mail —sin el texto del mensaje, con
  el link al portal— y **uno solo por racha sin leer**. Al analista, aviso in-app.
- Se puede escribir hasta **7 días después** de resuelto el expediente. Pasado ese plazo el hilo
  queda de lectura, con el motivo a la vista.

**Fuera de alcance, decidido**

- **No mueve el expediente.** Un mensaje no dispara `AWAITING_DOCUMENTATION`: ese estado lo pone la
  clasificación y lo cierra la subida del documento, y darle una segunda puerta haría que "esperando
  al asegurado" signifique dos cosas distintas.
- **No acepta adjuntos.** Siguen entrando por la pantalla de documentación, que les asigna tipo,
  dispara la reclasificación y los manda al análisis del modelo — todo eso se saltearía un archivo
  subido por el chat.
- **No es tiempo real.** Sondeo cada 15 s: la decisión #13 es REST stateless y nada acá justifica
  estrenar WebSockets.

**Por qué importa**
No había ningún canal. El mail de rechazo dice textual *"si querés conocer los motivos o no estás
de acuerdo, podés comunicarte con nosotros"* y hasta ahora esa frase no llevaba a ningún lado. Del
lado del analista, pedir una aclaración implicaba salir del sistema, con lo cual el pedido no
quedaba registrado en ningún lado.

**Notas técnicas**
`case_message` por tenant (`db/migrations/2026-08-30-chat-asegurado.sql`), `CaseMessageService` +
`CaseMessageController` (`GET`/`POST /cases/{id}/messages`, `POST .../read`), reusando
`CaseAccessPolicy` para el acceso y la tabla `notification` para el aviso. El plazo de los 7 días
es `arbiter.messaging.reply-window-days`. El componente del hilo (`app-case-chat`) es uno solo para
los dos portales.

---

# Decisiones de negocio (no son historias)

Resueltas al planificar el sprint 9 (26/08), salvo la que sigue marcada pendiente.

**1 · `fraud_determined` — ✅ respondida. `destination` — sigue pendiente.**
No eran dos incógnitas del mismo tamaño. `fraud_determined` **ya está implementado**:
`FraudRecordService.register()` lo pone en `true` cuando un analista determina fraude sobre un
caso — con o sin respaldo pericial (`ExpertAssessment` + `ExpertVerdict.FRAUD_CONFIRMED`), acotado
a los estados donde la determinación tiene sentido (`PENDING_ANALYST_REVIEW`, `REJECTED` — nunca
sobre un `APPROVED`, pagar y marcar fraude a la vez se contradicen), y con el registro completo del
lado de `classification-service` (quién lo declaró, cuándo, con qué respaldo). No es un stub.

`destination` sigue sin ninguna referencia en todo el código (`grep` no devuelve nada) y sin
valores definidos en ninguna fuente — el DER la dibuja como un `VARCHAR(40)` suelto. Esta parte de
la decisión sigue abierta: ¿es una salida real del proceso (pago / rechazo / derivación a
investigación) o quedó de un modelo viejo que `fraud_determined` + el flujo de peritaje ya
reemplazaron? Dado que la funcionalidad real (derivación a perito, determinación de fraude) ya se
construyó por otro lado, es candidata a **dropearse del DER** en vez de implementarse — a confirmar
con el equipo.

**2 · `is_individual` es la negación de `covers_family_group` — ✅ confirmado, es un detalle del DER, no una historia.**
Confirmado por el equipo. No genera card de desarrollo: es una corrección del DER (dropear la
columna redundante o documentar la derivación), no un cambio de código ni de comportamiento.

**3 · ¿Modelamos pólizas colectivas? — ✅ no, decidido.**
No se modelan. `Tomador` y `N° de certificado` quedan afuera de la ficha del expediente, tal como
están hoy — cada póliza sigue siendo individual, 1:1 con su certificado.

---

## Del handoff de Ollama por CPU (borrado el 25/08)

Lo único que seguía abierto de ese documento; el resto era registro de bugs ya arreglados, y la
advertencia del tag `:8b-instruct` vive mejor explicada en `CLAUDE.md`.

- **Historia "Conversación" — sin decidir.** No hay entidad de mensajería, así que es de punta a
  punta: entidad, endpoints, panel. Falta confirmar el alcance antes de estimarla. (Su hermana,
  H0031 "Datos extraídos", se implementó el 18/08.)
- **`RulesRestAdapterTest` no valida query params.** No es bloqueante, pero es la razón por la que
  un bug de parámetros pasó desapercibido — la clase de hueco que se repite.
