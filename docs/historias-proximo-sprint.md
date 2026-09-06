# Historias de usuario — próximo sprint

**Origen:** hallazgos de la tanda de fixes del 31/08–01/09 que no entraban en esa entrega.

Cada bloque es **una card de Trello**: el título va en el nombre de la card y el resto en la
descripción. La numeración sigue desde H0034, el último de `historias-enhancements.md`.

---

## H0035 · La lectura de la clasificación trae todo el historial para quedarse con una fila

**Como** analista de siniestros
**quiero** que abrir un expediente lea solo la clasificación vigente
**para** que el detalle no se vuelva más lento con cada reintento y el log deje de llenarse de
advertencias que tapan las que sí importan.

**Criterios de aceptación**
- Abrir el detalle de un expediente deja de emitir `HHH90003004: firstResult/maxResults specified
  with collection fetch; applying in memory`.
- La consulta trae **una** fila de `llm_analysis` con sus `llm_reason`, no todas las del expediente.
- El detalle sigue mostrando exactamente la misma clasificación que hoy: la de la **última** corrida,
  con todos sus motivos. Un expediente reclasificado varias veces sigue mostrando la más reciente.
- Un expediente sin clasificación (Fast Track, o todavía en curso) sigue resolviendo sin error.
- El comportamiento queda cubierto por un test contra Postgres real, no solo con el repositorio
  mockeado: los unitarios de hoy no ven la diferencia porque el problema es cómo Hibernate arma
  el SQL.

**Por qué importa**
`LlmAnalysisRepository.findFirstByCaseIdOrderByIdDesc` combina `findFirst` —que Spring Data traduce
a `maxResults = 1`— con `@EntityGraph(attributePaths = "reasons")`, que es un fetch de colección.
Hibernate no puede aplicar el límite en SQL sin arriesgar un resultado mal recortado, así que trae
**todas** las filas de `llm_analysis` del expediente con **todos** sus motivos y descarta el resto
en memoria.

Hoy no duele: `llm_analysis` es append-only con una fila por corrida, o sea una a tres por
expediente. Pero crece con cada reclasificación y cada reintento manual, y el expediente que más se
reclasifica es justo el que más veces se abre. Además el warning salta en **cada poll** del frontend
mientras el expediente está en `PENDING_CLASSIFICATION`, y ese ruido constante es el que hace que
nadie mire los logs cuando aparece algo real.

Se detectó mirando los logs del smoke test de coberturas (01/09): tres warnings por poll, cada
veinte segundos.

**Notas técnicas**
Es una sola consulta, con dos llamadores en
`ClassificationResultsService` (líneas ~191 y ~236). El arreglo estándar es partirla en dos: una
consulta liviana que resuelva el id de la última corrida (`select max(id) ... where case_id = ?`, o
un `findFirst` sin `@EntityGraph`, que sí puede limitar en SQL), y después traer esa fila con el
grafo por id. También sirve un `@Query` con subconsulta.

`RiskAnalysisRepository.findFirstByCaseIdOrderByIdDesc` tiene la misma forma **pero no el
`@EntityGraph`**, así que no sufre el problema — no hace falta tocarla, y conviene no hacerlo para
que el diff quede acotado a la consulta que sí falla.

Ojo con no cambiar el orden: tiene que seguir siendo la fila de **id más alto**, que es la corrida
más reciente. `ClassificationResultsService` ya depende de eso — al reclasificar, la tabla es
append-only y la anterior queda debajo.

---

## H0036 · Un rules-service caído deja entrar denuncias sin verificar y nadie las vuelve a mirar

**Como** analista de siniestros
**quiero** que una denuncia que entró sin poder verificar su documentación quede marcada y se
vuelva a verificar sola
**para** que una caída de rules-service no me deje expedientes que parecen completos y no lo están.

**Criterios de aceptación**
- Un alta que no pudo leer la agenda documental queda registrada como **entrada sin verificar**,
  con la marca persistida en el expediente (no solo en el log).
- Un proceso reintenta la verificación de esos expedientes cuando rules-service vuelve a responder.
- Si al reintentar faltaba documentación obligatoria, el expediente pasa a `AWAITING_DOCUMENTATION`
  y se le avisa al asegurado qué falta — el mismo aviso que ya existe para los faltantes.
- Si no faltaba nada, se limpia la marca y el expediente sigue su curso normal, sin rastro para el
  analista.
- El expediente **nunca** se rechaza por esto: la caída es nuestra, no del asegurado.
- El reintento es idempotente: correrlo dos veces sobre el mismo expediente no duplica avisos ni
  transiciones.

**Por qué importa**
Desde el 02/09 `cases-service` exige la agenda documental en el alta
(`CaseServiceImpl.assertRequiredDocumentsPresent`), y el wizard hace lo mismo del lado del
asegurado. Las dos verificaciones dependen de que rules-service conteste. Cuando **no** contesta,
las dos dejan pasar la denuncia a propósito: dejar al asegurado afuera porque un servicio nuestro
está caído sería peor que tomar el caso y verificarlo después.

El problema es que ese "después" hoy no existe. La denuncia entra, el expediente queda igual que
uno verificado, y nadie vuelve sobre él. En el mejor caso el gate de documentación faltante del
motor lo agarra en la clasificación; en el peor —si esa corrida también falla o la agenda cambia—
el expediente llega al analista aparentando estar completo.

Distinguir los dos casos ya está hecho en las tres capas (agenda vacía = "no pide documentos";
agenda ilegible = "no sabemos"), así que lo que falta es solo persistir la segunda y retomarla.

**Notas técnicas**
`RulesServiceClient.requiredDocumentTypes` ya devuelve `null` cuando no pudo leer, distinto de la
lista vacía. El llamador es `CaseServiceImpl.assertRequiredDocumentsPresent`, que hoy hace
`return` en ese caso — ahí va la marca.

Encaja con lo que el módulo ya hace: la clasificación es asincrónica con reintento
(`ClassificationRefreshScheduler`), y `AWAITING_DOCUMENTATION` no es exclusivo de este camino — un
expediente también cae ahí por documentación rechazada. O sea que el estado y el patrón de
scheduler ya existen; falta esta causa.

Registrado en el doc de gaps como §13, punto 2.

---

## H0037 · La bandeja mezcla los expedientes en curso con los que ya están cerrados

**Como** analista de siniestros
**quiero** que la bandeja me muestre por defecto solo los expedientes en curso
**para** no tener que descartar a ojo los aprobados, rechazados y caducados cada vez que entro a
ver en qué trabajar.

**Criterios de aceptación**
- La bandeja arranca mostrando solo los expedientes **en curso**: los cinco estados no terminales
  (`PENDING_CLASSIFICATION`, `PENDING_ANALYST_REVIEW`, `CLASSIFICATION_FAILED`,
  `AWAITING_DOCUMENTATION`, `PENDING_EXPERT_REPORT`).
- Hay forma explícita de ver los **cerrados** (`APPROVED`, `REJECTED`, `LAPSED`) y de ver **todos**.
  El recorte se ve en pantalla: nunca se esconde trabajo sin decirlo.
- El recorte **cruza** con las lentes que ya existen (Míos · Todos · Sin asignar · Alerta de
  fraude), no compite con ellas: "míos y en curso" tiene que ser posible. O sea que no es una
  quinta lente en la misma fila.
- Los conteos de las lentes respetan el recorte: parado en "En curso", "Míos 4" son 4 expedientes
  en curso, no 4 sobre el total histórico.
- El filtro de estado de la barra sigue mandando: elegir "Aprobado" en el select muestra los
  aprobados aunque el recorte esté en "En curso" — el recorte se ajusta solo a "Todos" en vez de
  devolver una lista vacía que el analista no puede explicarse.
- Un expediente con el **plazo de respuesta vencido sigue siendo "en curso"** y no se oculta.
  Vencido (semáforo del art. 56) no es cerrado: es el expediente más urgente que hay. El único que
  sale de la lista por vencimiento es el `LAPSED`, que ya está cerrado.
- El recorte sobrevive a paginar, ordenar y volver del detalle de un expediente.
- La exportación exporta **lo que se está viendo**, recorte incluido — igual que ya hace con la
  lente.
- El vacío distingue los dos casos: "no tenés expedientes en curso" no es lo mismo que "no hay
  expedientes".

**Por qué importa**
La bandeja es la pantalla de trabajo diario del analista, y hoy le muestra todo el caseload
histórico de la aseguradora en la misma lista. Con pocos expedientes se nota poco; en régimen la
proporción se invierte sola, porque los cerrados solo se acumulan y los en curso se vacían. Cada
expediente resuelto que sigue apareciendo es una fila que el analista tiene que leer y descartar
para encontrar las que le tocan.

`LAPSED` lo empeora: los caduca `LapseSweepScheduler` sin que nadie los toque, así que aparecen en
la lista expedientes que nunca vio pasar y que ya no puede trabajar.

Filtrar por estado a mano no lo resuelve: el select toma **un** estado por vez, y "en curso" son
cinco.

**Notas técnicas**
`GET /api/v1/cases` acepta `status` de a uno (`@RequestParam CaseStatus status` en
`CaseController.listCases`), así que "en curso" no se puede expresar con lo que hay hoy. Lo más
chico que funciona es un parámetro nuevo tipo `scope=open|closed|all`, resuelto en
`CaseSpecifications` contra el conjunto de estados terminales que **ya** define
`CaseStatusService.TERMINAL_STATUSES` — que no se duplique la lista, o el día que aparezca un
sexto estado la bandeja y la máquina de estados van a discrepar en silencio.

El endpoint de conteos (`lensSummary`) tiene que aceptar el mismo parámetro. Si no, los números del
toggle contradicen a la tabla, que es peor que no tenerlos.

En el frontend el tipo `Lens` no se toca: el recorte es un signal aparte que entra en
`activeFilters()` de `BandejaComponent`. Por ahí lo heredan los conteos y la exportación sin tocar
cada llamador, que es justo lo que hace que el criterio "los conteos respetan el recorte" salga
gratis. `isEstadoFinal()` (`core/models/estado.ts`) ya define qué es un estado final del lado del
cliente, pero **el recorte tiene que viajar al backend**: filtrar la página ya traída deja páginas
de tamaño variable y un total que miente.

Ojo con el default del backend: el portal del asegurado consume el mismo listado
(`InsuredCaseAggregator.findOwnCases`), así que si `scope` sale con default `open` el asegurado
deja de ver sus siniestros resueltos. Conviene default `all` del lado del servidor y que la bandeja
mande `scope=open` explícito — el default de la pantalla es de la pantalla, no del endpoint.

`dueSoon` ya existe en el backend como lente "Por vencer" y todavía no está cableado en la bandeja
(el tipo `Lens` no lo tiene). Es otra cosa que esto y, si se suma, va en la fila de lentes.

---

## H0038 · Un Fast Track llega sin decir por qué calificó, y se lee como si aprobara

**Hecha el 05/09/2026.** Se implementó como se describe abajo; las notas técnicas quedan con la
decisión que se tomó, no con las opciones que estaban abiertas.

**Como** analista de siniestros
**quiero** que un expediente en Fast Track me muestre qué criterios cumplió y que la decisión sigue
entera de mi lado
**para** poder resolverlo en cualquiera de las dos direcciones sin reconstruir a mano por qué entró
al carril rápido.

**Criterios de aceptación**
- La pantalla deja claro que Fast Track **no es una recomendación**: es que el expediente **no pasó
  por el modelo**. Ni el texto ni el color sugieren aprobar — un Fast Track se puede aprobar o
  rechazar, y las dos salidas son igual de normales.
- El analista ve **los criterios que el gate verificó**, uno por uno y con el valor evaluado: monto
  reclamado sobre la suma asegurada contra su tope, siniestros previos en la ventana contra su
  límite, antigüedad de la póliza contra el mínimo, póliza al día, documentación requerida
  presente. Son exactamente los que `FastTrackValidator` ya calcula y hoy se tiran.
- Esos criterios quedan **persistidos**, no recalculados al abrir el expediente: se muestra lo que
  se evaluó en esa corrida aunque el referente haya cambiado los umbrales después — mismo criterio
  que `policy_snapshot`, y lo que pide la auditoría de la Disposición 2/2023.
- Un expediente reclasificado muestra los criterios de la **última** corrida, no los de la anterior.
- Nada de esto llega al asegurado: sigue viendo su estado simplificado, sin criterios internos.
- No se inventa una confianza: en Fast Track no hay inferencia, y el "100%" sigue sin mostrarse
  (eso ya está bien resuelto hoy).

**Por qué importa**
`FastTrackValidator` arma la lista de motivos —los cumplidos *y* los incumplidos— y
`ClassificationOrchestrator.fastTrackResponse` la mete en `factors`. Ahí muere:
`ClassificationResultsService.saveResult`, cuando el resultado es determinístico, no escribe fila de
`llm_analysis` (el modelo no corrió) y solo marca el flag con `markFastTracked`; después `getStatus`
devuelve `factors = null` explícitamente. O sea: el trabajo de evaluar los criterios se hace, se
loguea, y no llega a la pantalla.

Lo que le queda al analista es una etiqueta —"Fast Track"— y una aclaración genérica. Para saber
por qué calificó tiene que abrir la solapa de análisis y rearmar la cuenta él: mirar la suma
asegurada del snapshot, el monto reclamado, los siniestros previos, y compararlos con los umbrales
que están en otra pantalla (Reglas). Eso es justamente lo que el gate ya hizo.

El riesgo de fondo es peor que la incomodidad: sin los criterios a la vista, "Fast Track" se lee
como *"esto está bien, dale aprobar"*. Y no es eso. Es "acá no hay recomendación de nadie, decidí
vos" — la decisión #5 (human-in-the-loop) en su forma más literal, porque es el único camino donde
nadie opinó antes que el analista. Un carril rápido que en la práctica se aprueba de taquito es
resolución automática con un humano de firma, que es exactamente lo que la decisión #5 y la
Disposición 2/2023 buscan evitar.

**Notas técnicas (lo que se hizo)**
`FastTrackValidator.Result` ahora devuelve, además de los motivos en prosa, un `RuleFinding` por
criterio comparado — el mismo tipo que ya usan los otros evaluadores, así que el orquestador los
suma a `ruleFindings` y `ClassificationResultsService` los escribe en `rule_result` sin cambios.

Los criterios van a **`rule_result`**, con cinco tipos nuevos en `RuleType` (`FT_AMOUNT_RATIO`,
`FT_PRIOR_CLAIMS`, `FT_POLICY_AGE`, `FT_POLICY_UP_TO_DATE`, `FT_REQUIRED_DOCS`). El javadoc del enum
decía que los tipos de configuración "no dejan `rule_result`", y esa partición ya era falsa antes de
esto: `COVERS_FAMILY_GROUP` y `CLAIM_EXHAUSTS_COVERAGE` son columnas de `coverage`, no filas de
`insurer_rule`, y sí dejan fila. Quedó reescrito en tres familias, con el eje real explicitado: ser
fila de `insurer_rule` y ser auditable son dos cosas distintas.

`rule_id` va en `null`: los umbrales sí viven en una fila `FAST_TRACK` de `insurer_rule`, pero su id
no viaja — `/internal/fast-track` devuelve el mismo DTO de configuración que escribe el referente, y
no lo lleva. El umbral queda en `evaluated_value` (`ratio=21,9% max=50,0%`), así que la fila se
audita sola. Sumarle el id es una historia aparte y toca el contrato de rules-service.

En pantalla van en **una card propia**, "Criterios del carril rápido", separada de "Reglas
evaluadas": un "No cumple" significa cosas distintas en cada tabla —no cubierto vs. no va por el
carril rápido— y mezclarlos hacía leer una exclusión de cobertura donde solo hubo un umbral de
agilidad. La card de recomendación ahora dice "Sin recomendación: el modelo no intervino" y que se
puede aprobar o rechazar igual que cualquier otro.

De paso: `getRuleResults` devuelve **solo la última evaluación de cada regla** (`rule_type` +
`rule_id`). La tabla es append-only y cada reclasificación escribía su tanda, así que un expediente
reintentado tres veces mostraba la misma regla tres veces — y si el referente había cambiado un
umbral en el medio, dos veces con resultados opuestos. No se borra nada: las filas viejas siguen en
la tabla, que es lo que la auditoría necesita.

**El seed quedó al día**, para que levantar la BD de cero dé lo mismo que produce el motor:

- `init-multitenant.sql` ahora siembra los **umbrales de Fast Track como configuración real**, una
  fila `FAST_TRACK` por cobertura con su `configuration` (cobertura 1: 50%, 0 previos, póliza al
  día; cobertura 2: 30%). Antes eran dos filas de ejemplo **sin `coverage_id` ni `configuration`**,
  y `FastTrackRuleService` busca por (ramo, cobertura): no las encontraba nunca. O sea que el
  referente veía la pantalla de Fast Track en blanco y los umbrales que de verdad decidían salían
  del baseline de `MockRulesAdapter` — de código, contra la decisión #12. `seed-demo.sql` agrega la
  de Daño accidental de Provincia (cobertura 3, tope 60%), que no tenía ninguna: sin ella el ramo
  entero no podía resolver por Fast Track.
- Las filas de `rule_result` pasaron a los tipos `FT_*` con el valor comparado, calculado de los
  datos de cada expediente contra esos umbrales. Sin fila de documentación: ningún expediente del
  fixture tiene adjuntos, y un PASS ahí afirmaría que el gate verificó papeles que no existen.
- Las dos filas `COVERAGE_INCLUSION` pasaron a `COVERAGE_EXCLUSION` con el formato que escribe
  `CoverageRuleEvaluator`. `COVERAGE_INCLUSION` no existe en `RuleType`: la lista blanca nunca se
  implementó del lado Java.
- **Caso 11 (BBVA)**: reclamaba 470.000 sobre una suma asegurada de 500.000 —el 94%— y aun así
  figuraba como Fast Track. Con el tope de 50% de su cobertura nunca habría calificado, y era
  además el peor ejemplo posible del carril rápido. Quedó en 240.000 (48%), y su `risk_analysis`
  acompaña: 0,216 → LOW.

**Faltaba una cobertura entera y salió a la luz acá.** En el ramo Celulares de BBVA los hechos
generadores **Rotura accidental (1) y Caída (4) no los cubría ninguna cobertura**: solo existían
Robo y Hurto, cuyas listas negras son `[1,3,4]` y `[1,2,4]`. No era una decisión de producto — la
agenda documental sí tiene cargados los requisitos de los dos hechos, el selector del wizard filtra
por esas mismas listas (o sea que no podía ofrecerlos) y los casos 1 y 12 del fixture, dos roturas de
pantalla, colgaban de la cobertura de robo, que las excluye.

Se agregó **Daño accidental** al ramo Celulares de BBVA, con la forma de las otras dos: su lista
negra (`[2,3]`), sus umbrales de Fast Track, sus temporales (sin plazo de denuncia policial — una
rotura no tiene denuncia que presentar) y su fila en las pólizas **Premium**, por la misma suma que
robo (es el mismo equipo) y con franquicia 20%. Las Básico no la traen, así que a esos asegurados el
wizard sigue sin ofrecerles rotura ni caída, que ahora sí es una decisión de producto y no un
agujero. Los casos 1 y 12 pasaron a colgar de ella.

Solo BBVA: las coberturas son configuración de cada aseguradora y los ids son **por esquema**, así
que la 3 de BBVA y la 3 de Provincia (Daño accidental de Tecnología Portátil) no se pisan.

De la misma familia, en Provincia: el expediente 6 es un hurto que colgaba de la cobertura de robo
porque su póliza no tenía contratada la de hurto. Se le agregó la cobertura a esa póliza y el
expediente pasó a colgar de la que lo cubre.

**Verificado contra Postgres**, no leyendo el SQL: `init-multitenant.sql` + `seed-demo.sql` corren
limpios sobre una base vacía (contenedor `pgvector/pgvector:pg16`), y sobre el resultado no queda
ningún expediente colgado de una cobertura que excluya su hecho generador —salvo los casos 8 y 17 de
BBVA, que son a propósito el escenario "hurto no cubierto"—, ninguno apunta a una cobertura que su
póliza no tenga, y cada fila `FT_AMOUNT_RATIO` coincide con lo que daría el umbral configurado de su
cobertura contra el monto y la suma asegurada del expediente.

**Lo que sigue abierto:** en Provincia, el ramo Tecnología Portátil solo tiene Daño accidental, así
que robo y hurto de ese ramo no los cubre ninguna cobertura. Hoy no rompe nada —no hay ningún
expediente con esos hechos— pero el wizard tampoco puede ofrecerlos, y el propio encabezado de
`datos-aseguradoras.sql` dice que el ramo tiene las tres. Es la misma card que agregar coberturas,
con datos que hay que decidir (sumas por póliza).

---

## H0039 · Un siniestro que agota la cobertura deja la póliza sin saldo y nadie se lo dice

**Como** asegurado
**quiero** enterarme, cuando me aprueban un siniestro que consume mi cobertura, de que la póliza
quedó sin saldo y qué tengo que hacer para volver a estar cubierto
**para** no descubrirlo recién cuando denuncie el siguiente siniestro y me digan que no hay con qué
responderme.

**Criterios de aceptación**
- Al **aprobar** un expediente, Arbiter determina si esa liquidación **agota la cobertura**: por
  `coverage.claim_exhausts_coverage` (un solo siniestro la consume) o por acumulación (lo liquidado
  previo sobre esa misma cobertura más este siniestro alcanza su suma asegurada). Mismo criterio y
  mismos datos que `CoverageScopeEvaluator` — no una segunda regla que pueda contradecir a la
  primera.
- El hecho queda **persistido en el expediente**, con qué cobertura, qué suma asegurada y qué monto
  la consumió. Es lo que después explica, con números, por qué el próximo siniestro se frena.
- Al asegurado se le avisa junto con la aprobación: la cobertura de esa póliza quedó consumida por
  este siniestro y, para volver a estar cubierto, hace falta una póliza nueva. En castellano llano,
  sin umbrales ni vocabulario de motor de reglas — el asegurado no ve internas.
- Arbiter **no emite** la póliza nueva ni promete fecha: la emisión es del core de la compañía.
  Arbiter avisa, marca y deja el caso listo para que alguien lo tome.
- El referente ve las **pólizas con cobertura agotada pendientes de reemisión** en una lista propia.
  Sin eso el hand-off vive en un mail y se pierde: es la parte que hace que el aviso al asegurado no
  sea una promesa vacía.
- Cuando el asegurado arranca una denuncia nueva sobre una cobertura que Arbiter ya sabe agotada, se
  lo dice **en el wizard**, antes de que cargue documentación — no después de clasificar. Avisa, no
  bloquea: mismo *fail-open* que el precheck de elegibilidad de hoy, y el expediente llega marcado
  para el analista.
- Sin datos suficientes (sin suma asegurada, sin monto imputado, sin cobertura en el histórico) **no
  se afirma nada**: la regla no participa, igual que hoy en `evaluateSumInsuredLimit`. Decirle a
  alguien que se quedó sin póliza cuando no es cierto es peor que no decirle nada.
- El aviso no se dispara dos veces sobre el mismo expediente ni sobre una cobertura ya avisada.

**Por qué importa**
Hoy el agotamiento de la cobertura solo se detecta **al clasificar el siniestro siguiente**:
`CoverageScopeEvaluator.evaluateExhaustedCoverage` y `evaluateSumInsuredLimit` bloquean Fast Track y
le dejan el motivo al analista. Funciona, pero llega tarde para todos: el asegurado ya denunció, ya
cargó la documentación, ya esperó — y la respuesta es que no había nada con qué responderle. Es el
peor momento posible para enterarse.

En el medio queda alguien que cree estar cubierto y sigue pagando cuotas de una póliza sin saldo.
Para la compañía es un cliente que se va y una venta que no se hizo; para el asegurado, una
expectativa falsa que Arbiter tenía cómo desarmar y no desarmó. Y en Celulares el caso típico no es
raro: un robo total liquida la cobertura, el asegurado repone el equipo, y ese equipo nuevo —otro
IMEI— no está cubierto por nada.

Del lado del dato tampoco hay quien cierre el círculo: Arbiter **solo lee** `siniestro_historico`
(no hay un solo `INSERT` sobre esa tabla en el código), así que el consumo de la cobertura recién
existe cuando la compañía carga la liquidación en su propia base. Hasta entonces ni el propio motor
de Arbiter lo ve — y el asegurado puede denunciar de nuevo mañana sin que nada se lo advierta.

**Notas técnicas**
El punto de enganche es `CaseServiceImpl.recordAnalystDecision` (cases-service), que hoy valida la
transición, reenvía la decisión a classification-service y transiciona a `APPROVED`. El aviso sale
por `CaseNotificationService`, que ya notifica el cambio de estado al asegurado.

**Lo que hay que decidir antes, y es lo que abre esta historia:** *Arbiter no registra monto
liquidado.* `AnalystDecisionRequest` es `(analystId, decision, justification,
classificationAttempts)`; lo único cuantificado es `cases.claimed_amount`, que es lo que **pidió** el
asegurado, no lo que se **pagó**. Dos salidas:

1. el analista carga el monto liquidado al aprobar (campo nuevo en la decisión) — el agotamiento
   pasa a ser un hecho y no una estimación, y de paso `siniestro_historico` deja de ser la única
   fuente de "cuánto se consumió";
2. se calcula con el reclamado y todo lo que Arbiter diga es explícitamente una **estimación**
   ("según lo reclamado"), tanto en el aviso al asegurado como en la marca del expediente.

La 1 es más trabajo y más correcta; la 2 no obliga a tocar la decisión pero sí a que cada mensaje
diga que es aproximado. No se pueden tener las dos: un aviso categórico calculado sobre el monto
reclamado es un dato inventado.

`claim_exhausts_coverage` es booleano y de la cobertura, no un umbral del referente: cuando está en
`true` el agotamiento no depende de ningún monto y la decisión de arriba ni se plantea. La
acumulación contra `suma_asegurada` es la que necesita el monto.

**Depende de** que el expediente cuelgue de la cobertura correcta —el consumo se imputa a *esa*
cobertura, y cargarlo en la equivocada haría avisar que se agotó una que nadie tocó. Eso ya está
resuelto (era el "El expediente se cuelga de la cobertura equivocada" de `docs/temas-a-discutir.md`,
donde toda póliza apuntaba a *Robo de celular*), así que no bloquea: solo conviene verificar contra
Railway que los expedientes de *Hurto* cuelgan de Hurto antes de empezar a sumar montos.

**Fuera de alcance** (Arbiter no lo hace, y conviene que quede escrito): emitir la póliza de
reemplazo, cotizarla, reponer la suma asegurada o dar de baja la póliza consumida. Todo eso es del
sistema de la compañía. Lo de Arbiter es detectarlo, decirlo a tiempo y dejarlo anotado.
