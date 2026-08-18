# Handoff — Ollama por CPU: modelo, memoria y ventana del scheduler

Documento para retomar en otra conversación (o para que lo lea Fede). Fecha: 2026-08-18.
Branch: `feature/validaciones-polizas-reglas-core`.

> ⚠️ **Fede: los `mem_limit` de `docker-compose*.yml` están calibrados para ESTA máquina** (AMD
> Ryzen 7 7735HS, ~32GB RAM, WSL2 subido a 24GB por `.wslconfig` — ver bug #1 abajo), no son un
> valor universal. Si corrés esto en otra notebook o en un server con menos RAM, `mem_limit: 14g`
> para Ollama solo (más 2g de CLIP, ~5g del resto) puede no entrar, y si la máquina tiene más RAM
> disponible, estos límites la dejan sin usar de más — quedate corto a propósito por diseño (ver la
> nota en el propio `docker-compose.yml`), pero corto para 24GB no es corto para 64GB. Antes de
> asumir que un problema de memoria en otra máquina es "el mismo bug de siempre", correlo con
> `docker stats` ahí y recalculá los límites contra la RAM real de esa máquina — no copies los
> números tal cual. Mismo cuidado con `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75.0`: es relativo
> al `mem_limit` del contenedor, así que si cambiás uno tenés que revisar el otro.

---

## El disparador

El caso 16 (Provincia, `POL-TEC-2026-311`, Tecnología Portátil) se usó como caso de prueba real de
punta a punta — 3 documentos adjuntos, clasificación asincrónica — y terminó destapando cuatro bugs
de infraestructura independientes, todos alrededor de correr Qwen3-VL por CPU sin GPU. Ninguno es
un defecto de diseño del módulo: son supuestos que se rompieron al correr contra hardware real
lento, en vez del entorno rápido que probablemente se usó para escribir el código original.

**Estado al cortar: nada commiteado, sin probar con `mvn test` (el usuario corre los tests él
mismo). El caso 16 sí quedó resuelto en la base — clasificó bien (`LLM_NO_RECOMIENDA_APROBAR`,
confianza 0.95) y está en `PENDING_ANALYST_REVIEW`, asignado a Federico Moccia.**

```bash
git status --porcelain
```
va a mostrar:
- `CLAUDE.md` (M — nota sobre el tag del modelo)
- `docker-compose.yml`, `docker-compose.railway.yml` (M — límites de memoria + `OLLAMA_MODEL`)
- `cases-service/.../resources/application.yml` (M — ventana del scheduler)
- `classification-service/.../adapters/OllamaClient.java` (M — `think`, logging de progreso)
- `classification-service/.../adapters/OllamaClaimClassifier.java` (M — `think: false`)
- `classification-service/.../adapters/OllamaDocumentAnalyzer.java` (M — `think: false`)
- `classification-service/.../adapters/RulesRestAdapter.java` (M — falta `claimCause`)
- `classification-service/.../config/OllamaConfig.java` (M — sin read timeout)
- `classification-service/.../resources/application.yml` (M — `OLLAMA_MODEL` default)
- `classification-service/README.md` (M)
- `classification-service/src/test/.../OllamaDocumentAnalyzerTest.java` (M — firma nueva de `chat`)
- `cases-service/.../dto/CaseResponse.java` (M — `analysisDetail: String` → `analysisReasons:
  List<String>`, DER)
- `cases-service/.../services/CaseServiceImpl.java` (M — `detailOf` → `reasonsOf`)
- `cases-service/src/test/.../CaseServiceImplTest.java`,
  `cases-service/src/test/.../CaseControllerTest.java` (M — aserciones actualizadas a la lista)
- `arbiter-frontend/.../core/models/expediente.ts` (M — mismo rename)
- `arbiter-frontend/.../expediente-detail.component.{ts,html,scss}` (M — motivos de la
  clasificación movidos a tab "Razones", ver más abajo)

**Fuera del repo, no versionado:**
- `C:\Users\User\.wslconfig` — subió la memoria de WSL2 de 16GB (default) a 24GB.
- `.env` local — `OLLAMA_MODEL` tenía `qwen3-vl` (thinking) pisando el default del compose; el
  usuario lo cambió a mano a `qwen3-vl:8b-instruct`. **Si alguien más clona el repo, necesita este
  mismo cambio en su propio `.env`** — no hay forma de forzarlo desde el compose porque
  `${OLLAMA_MODEL:-default}` respeta la env var si existe.

---

## Los cuatro bugs, en el orden en que aparecieron

### 1 · Sin límites de memoria → OOM real

Ningún contenedor tenía `mem_limit` ni ninguna JVM tenía `-Xmx`. Cada JVM se dimensiona sola contra
la memoria **total** que ve (heap máximo = 25% de la RAM disponible por default) — con 5 servicios
Java más Ollama más el sidecar de CLIP compitiendo sin límite, el kernel terminó matando
`llama-server` a mitad de una clasificación:

```
llama-server process no longer running — signal: killed
```

**Fix**: `mem_limit` explícito por servicio en ambos `docker-compose*.yml` (Ollama 14g, CLIP 2g,
cases/classification 1-1.5g, auth/rules/reports 768m — suman ~20.75GB de los 23.4GB que ve Docker
tras subir WSL2 a 24GB) + `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75.0` en los cinco módulos Java.

### 2 · `qwen3-vl` resuelve al tag `-thinking`, que no se puede apagar

El tag pelado (`qwen3-vl`, el que usa `ollama pull qwen3-vl`) resuelve a `qwen3-vl:8b-thinking`.
Ese modelo razona antes de cada respuesta, y **el razonamiento no se puede desactivar**: se probó
contra Ollama 0.30.8 que tanto `think: false` en el request como la directiva `/no_think` de Qwen
se ignoran — el modelo piensa igual.

Combinado con `num_predict` (que sí hacía falta agregar, ver más abajo, para no dejar que un loop
de repetición corra sin límite), esto era letal: el modelo gastaba el presupuesto entero de tokens
razonando (van a `message.thinking`, no a `message.content`) y la respuesta volvía **vacía**. Se
midió con precisión: dos documentos seguidos tardaron 27,1 y 27,9 minutos — exactamente lo que
tarda generar 4096 tokens a ~2,5 tok/s — y devolvieron 0 caracteres los dos.

**Fix real**: cambiar de modelo, no de parámetro. `qwen3-vl:8b-instruct` es la misma familia (8B,
Q4_K_M, 6.1GB, visión) sin la fase de razonamiento. `think: false` se dejó igual en el código —es
la instrucción correcta a mandarle a la API, y no hace daño— pero quien de verdad resuelve el
problema es el tag del modelo. Ver la nota nueva en `CLAUDE.md` (sección de comandos de Ollama).

Con el modelo correcto, una corrida completa (3 documentos + clasificación) tardó **53 minutos**
en vez de horas / OOM.

### 3 · `RulesRestAdapter` no mandaba `claimCause` — 400 en cada clasificación

La feature "documentación segmentada por hecho generador" (mergeada desde `develop`) le agregó un
`@RequestParam String claimCause` obligatorio a `GET /api/v1/rules/document-requirements/internal`
en rules-service. `classification-service` (que también viene de este merge, en la misma rama)
seguía llamando a ese endpoint solo con `coverageId` → **400 Bad Request** → la clasificación
entera moría a los pocos segundos de arrancar, siempre, para cualquier caso.

**Fix**: `RulesRestAdapter.getRules` ya tenía el `claimCauseId` a mano (es un parámetro del método);
solo hacía falta pasarlo. Se revisaron los otros 6 endpoints internos que consume
`classification-service` — ninguno más tiene este desajuste, piden solo `coverageId`.

**Deuda de testing detectada de paso**: `RulesRestAdapterTest` arma un stub HTTP casero
(`com.sun.net.httpserver.HttpServer`) que responde a cualquier path sin validar query params — por
eso este desajuste de contrato pasó el CI sin que nadie se enterara. No se tocó (no era el foco),
pero vale la pena que alguien le sume una aserción de que el query string tiene lo que el endpoint
real exige.

### 4 · Ventana del scheduler (10 min) muy corta para CPU (hasta 1h+)

`ClassificationRefreshScheduler` (cases-service) tenía `max-attempts=120` / `interval-ms=5000` por
default en el código — **10 minutos de ventana total** antes de marcar `CLASSIFICATION_FAILED`.
Corriendo por CPU, una clasificación real puede tardar de 20 minutos a más de una hora. El
resultado: el scheduler se rendía **mientras la clasificación seguía corriendo en
`classification-service`**, que no tiene forma de avisarle a `cases-service` que sigue viva — el
expediente quedaba marcado como fallido con el resultado real llegando bien más tarde, sin que
nada lo reconciliara.

Se ve clarísimo en el historial de estados del caso 16: pasó a `CLASSIFICATION_FAILED` **siete
veces** en la sesión, casi siempre sin ningún error real de por medio — era el scheduler rindiéndose
antes de tiempo, una y otra vez, mientras se iban corrigiendo los otros tres bugs.

**Fix**: `interval-ms: 20000` / `max-attempts: 540` (3hs de ventana total) en
`cases-service/application.yml`, mismo criterio que el timeout de `OllamaClient` (holgado a
propósito: el rol de esto es dar por muerta una clasificación que de verdad no va a volver, no
optimizar cuánto se tarda en darse por vencido).

**Nota para cuando se retome**: esto es un parche del síntoma, no la causa. La causa de fondo es
que `cases-service` y `classification-service` no tienen ningún canal para que el segundo le avise
al primero "seguís vivo, dame más tiempo" — solo hay polling ciego con un timeout fijo. Si algún
día se agrega GPU (o se corre en un servidor sin las limitaciones de esta notebook), esta ventana
de 3hs queda sobredimensionada y no hace daño, pero tampoco resuelve el problema de fondo si en
algún momento hay clasificaciones genuinamente más lentas que 3hs.

**Reconciliación manual del caso 16**: cuando se encontró el bug #4, el caso ya estaba en
`CLASSIFICATION_FAILED` con el resultado real ya persistido en `classification-service` (se
confirmó por log: `Classification obtained — caseId=16 ... latency=3199238ms`). En vez de
reintentar (que hubiera vuelto a gastar ~53 minutos de Ollama para nada), se lo devolvió a mano a
`PENDING_CLASSIFICATION` con `classification_attempts=0` — el scheduler lo recogió en el tick
siguiente, encontró el resultado ya guardado vía `GET /api/v1/claims/16`, y lo transicionó solo a
`PENDING_ANALYST_REVIEW` sin tocar Ollama de nuevo. Es el mismo mecanismo que usa el botón de
reintento (`ClassificationServiceClient.refreshClassification`), solo que sin resetear la
clasificación que ya estaba bien.

---

## Bug aparte, del lado del front: los motivos de la clasificación no se mostraban en ningún lado

Reportado por el usuario mirando la respuesta de la API directamente: el JSON de
`GET /api/v1/cases/16` traía `analysisDetail` con los motivos de la clasificación, pero
`expediente-detail.component.ts`/`.html` no lo leían **en ningún lado**. No era un problema de
gating por status ni de permisos — el campo directamente no tenía binding.

Al ir a exponerlo apareció un segundo problema, de fondo: `analysisDetail` era un `String` armado
en `CaseServiceImpl.detailOf` con `String.join(", ", analysis.factors())`. `llm_reason`
(classification-service) es una tabla de **una fila por motivo** — el DER manda (ver `CLAUDE.md`,
"El DER es fuente de verdad"), así que aplanar a un string ahí ya era un desvío, más allá de que
el front no lo leyera.

**Fix — completo, backend y frontend, ambos hechos:**

- **Backend** (`cases-service`): `CaseResponse.analysisDetail: String` → `analysisReasons:
  List<String>`. `CaseServiceImpl.detailOf` → `reasonsOf`, devuelve `analysis.factors()`
  directo, sin `String.join`. Mismo criterio de antes para Fast Track: `List.of()`, no los
  motivos de la corrida anterior. Tests actualizados (`CaseServiceImplTest`,
  `CaseControllerTest`) — no se corrió `mvn test` (sin Maven en este entorno; el usuario corre
  los tests él mismo), pero se revisó a mano cada `new CaseResponse(...)` del repo (son dos:
  el de `CaseServiceImpl` y el helper `caseResponse(...)` de `CaseControllerTest`, que pasa
  `null` en esa posición — sigue siendo válido, `List<String>` acepta `null` igual que `String`).
- **Frontend**: `ExpedienteResponse.analysisDetail: string` → `analysisReasons: string[]`. Se
  sacó el listado que se había puesto (en una vuelta anterior de este mismo pedido) adentro de la
  card "Recomendación de Arbiter" — el usuario pidió específicamente que NO quedara ahí. Se movió
  a una tab nueva, **"Razones"**, que sigue el mismo patrón condicional que ya existía para
  "Peritaje" (`...(this.peritaje() ? [...] : [])`): solo aparece en la lista de tabs cuando
  `analysisReasons().length > 0`. Se evaluó reusar la tab "Datos extraídos" (todavía sin
  implementar) en vez de crear una nueva, pero es un concepto distinto — esa tab es para
  datos que la IA extrajo de la denuncia/documentos, no para los motivos de la clasificación —
  así que se descartó y se creó "Razones" aparte, como pidió el usuario si ninguna tab existente
  encajaba.

No se tocó el resto de los campos que trae la API (`riskBreakdown` sí tenía binding y se mostraba
bien vía `app-fraud-gauge`/similar — se confirmó leyendo el componente, no hacía falta tocarlo).

**Qué NO se hizo acá** (nada quedó pendiente de este pedido puntual — ítem cerrado): no se
agregó test unitario del componente porque no existe un spec file para `expediente-detail` en el
repo (se verificó con `Glob`); si se suma testing de componentes de esta pantalla más adelante, la
tab "Razones" y su gating condicional son un buen primer caso.

---

## Para Fede: chequear si el texto que devuelve el LLM trae markdown sin renderizar

Reportado por el usuario mirando la tab "Razones" ya en pantalla: el texto de los motivos "pareciera
tener formateo o algo, se ve raro porque tiene `**` como si quisiera ponerle negrita" — asteriscos
dobles sueltos, sin ningún efecto visual, en el medio de la oración. **No se confirmó todavía contra
una respuesta real** (no hay logs a mano de la corrida que la mostró — se perdieron al reiniciarse
el contenedor, ver más abajo), pero el código deja una hipótesis bastante fuerte de por qué pasa.

**Lo que se encontró leyendo el prompt (`classification-service/src/main/resources/prompts/classification-v4.md`):**
el prompt entero está escrito en Markdown pesado — `**Ramo:**`, `**reglas duras**`,
`**hechos establecidos**`, `**recomendación no vinculante**`, etc., todo el documento usa negrita
para enfatizar. Nada en la sección "Tarea de clasificación" le dice al modelo que sus propios
`factores` (el array de strings que arma como salida) tienen que ser **texto plano, sin Markdown**.
Es plausible que el modelo, viendo que todo el prompt que recibe usa `**palabra**` para enfatizar,
imite el mismo estilo al redactar sus propios motivos — y como `factors` en el schema
(`OllamaClaimClassifier.OUTPUT_SCHEMA`) es `{"type": "array", "items": {"type": "string"}}` sin
ninguna restricción de formato, nada se lo impide. Del otro lado, `analysisReasons` viaja tal cual
llega del modelo — sin ningún paso de sanitización — y el template lo interpola crudo
(`{{ reason }}`, Angular, sin parser de Markdown), así que si el modelo manda
`"**Múltiples siniestros previos** sin objeción reciente"`, eso es exactamente lo que ve el
analista, asteriscos incluidos.

**Qué chequear / decidir:**
1. Confirmarlo contra una respuesta real — correr una clasificación y mirar `content` en el log
   (`OllamaClaimClassifier`, `log.debug("[Ollama] Raw content: {}", content)`, o el campo
   `output_raw`/`llm_reason.reason` en la base) para ver si de verdad trae `**`.
2. Si se confirma, dos caminos (no excluyentes, pero probablemente alcanza con uno):
   - **Instruir al modelo**: sumar una línea explícita en la sección "Tarea de clasificación" del
     prompt — algo como "Los factores van en texto plano, sin Markdown ni asteriscos" — es el fix
     más barato y ataca la causa, no el síntoma.
   - **Sanear en el parseo**: `OllamaClaimClassifier.parseResponse` podría limpiar `**`/`*`/`_` de
     cada factor antes de armar el `ClassificationResponse`, como red de seguridad aunque el prompt
     se lo pida bien (los modelos no siempre obedecen instrucciones de formato al 100%).
3. Lo que **no** parece correcto es renderizar Markdown en el frontend (que el `**` se vuelva
   negrita de verdad): el resto de la pantalla no usa Markdown en ningún lado, y el input viene de
   un modelo, no de alguien escribiendo con intención de formato — más fácil de razonar si el dato
   que persiste `llm_reason` es siempre texto plano, auditable, sin marcas.
4. Vale la pena mirar si el mismo problema aparece en `DocumentExtraction.transcription`/
   `visualFindings` (los prompts de extracción, `extraccion-documento-v3.md`, también usan
   Markdown) — no se revisó en esta pasada, pero es la misma causa potencial.

---

## Para Fede: UX review pendiente en esta misma pantalla — posible card muerta/duplicada

Al mover los motivos a la tab "Razones", el usuario miró un screenshot de la tab "Resumen" y
preguntó por la card **"Sugerencia del sistema"** (sidebar, al lado de "Analista asignado"), que
se ve permanentemente vacía — "Sugerencia e indicadores del modelo / Pendiente de datos de IA".
Quedó como tarea de UX, no de código: hace falta una decisión de diseño, no un fix.

**El hallazgo, mirando el HTML (`expediente-detail.component.html`):**

| Superficie | Dónde | Estado |
|---|---|---|
| Card "Recomendación de Arbiter" | fila superior, siempre visible | **poblada** — clasificación + confianza + nota |
| Tab "Riesgo" | tab | **poblada** — score + desglose por factor |
| Tab "Razones" (nueva, esta vuelta) | tab | **poblada** — motivos del LLM, uno por fila |
| Card **"Sugerencia del sistema"** | tab Resumen, sidebar | **stub fijo**, sin ningún `@if` — nunca se cableó a nada |
| Tab "Datos extraídos" | tab | **stub fijo** — pero por otra razón, ver abajo |

**Hipótesis (no confirmada con nadie del equipo, solo por lectura de código): "Sugerencia del
sistema" es UI muerta.** Lo que promete mostrar — sugerencia del modelo + indicadores — ya está
cubierto por las tres filas de arriba de la tabla. No hay ningún campo de la API que esta card
lea (a diferencia de "Datos extraídos", ver abajo): es un `<app-empty-state>` incondicional, no
gateado por ningún `@if (algo(); as x)`. Probablemente sea un placeholder de una etapa temprana
de la pantalla, de antes de que existieran "Recomendación de Arbiter"/"Riesgo"/"Razones", y nadie
lo sacó al agregar esas tres piezas.

**"Datos extraídos" es harina de otro costal — no es candidata a la misma sospecha.** Su propio
label dice "Datos extraídos por IA **+ coincidencia con la póliza**": es el resultado de
OCR/visión sobre los documentos/imágenes (ej. IMEI o número de serie leído) contra lo que dice la
póliza (`BienAsegurado`) — no los motivos de la clasificación. Ese dato **no existe en el
contrato de la API todavía** (ni `CaseResponse` ni `ExpedienteResponse` tienen ningún campo de
extracción estructurada) — es una feature de otro alcance, sin construir, no una card duplicada.
Por eso "Razones" no se pisó con "Datos extraídos" al elegir dónde poner los motivos.

**Lo que le pediría a Fede, con toda esta info ya reunida:**
1. Confirmar si "Sugerencia del sistema" es dead code de una iteración anterior de la pantalla o
   si tenía un propósito distinto en mente (ej. un resumen más corto que la card de arriba, para
   quien solo mira la sidebar). Si es lo primero, sacarla; si es lo segundo, redefinir qué
   contenido único debería tener para no repetir "Recomendación de Arbiter".
2. Si se saca, revisar el layout de `.col-side` en la tab Resumen — hoy son dos cards
   (`Analista asignado` + `Sugerencia del sistema`); sacando la segunda, ver si conviene que
   "Analista asignado" quede sola o si hay algo mejor para poner ahí.
3. Confirmar el alcance real de "Datos extraídos" (¿se va a construir la extracción OCR +
   matching contra póliza en este sprint, o queda para más adelante?) — no bloquea nada de lo de
   arriba. Ver la historia abajo para lo que ya existe del lado del backend.

**Actualización sobre "Datos extraídos" y "Conversación": se ocultaron de la barra de tabs**
(mismo criterio que "Razones"/"Peritaje" — una tab permanentemente vacía es ruido), pero **no se
borró nada del código**: el `TabId` y el `@case` del template siguen ahí, solo se sacaron de la
lista que arma `tabs()`. Quedan como historias abajo, con lo que hace falta del lado del backend
para que cada una tenga sentido y se pueda volver a sumar a la lista.

### Historia: tab "Datos extraídos" — **para implementar, no solo para evaluar**

> Como analista, quiero ver los datos que el modelo leyó de cada documento adjunto (IMEI, importe,
> fecha, a quién le pasó) y si coinciden con lo que dice la póliza, para no tener que abrir cada
> PDF/imagen a mano y comparar contra el contrato.

**Fede: esto se carga como trabajo a hacer, no como una idea para el backlog general.** No es un
bug — nada se rompe si no se hace — pero es importante: el dato ya se calcula en producción
(`classification-service` lo genera en cada clasificación) y hoy se tira. Es la card
**H0031 de `docs/historias-enhancements.md`** — ese es el detalle canónico de los 4 pasos
(entidad en classification-service → endpoint REST interno → campo en `CaseResponse` → el
`computed` del componente que vuelva a sumar `'datos'` a `tabs()`, usando "Razones" como
referencia de cómo se gatea una tab por presencia de datos). No lo duplico acá.

**Lo que ya existe (no hay que construirlo de cero):** `classification-service` ya extrae
exactamente esto — `DocumentExtraction.Fields` (`classification-service/.../dto/DocumentExtraction.java`)
trae `documentDate`, `amount`, `itemDescription`, `imei`, `affectedParty` por documento, más
`visualFindings` (señales de manipulación). Lo arma `OllamaDocumentAnalyzer` y lo consume
`ClassificationOrchestrator` para armar el prompt y alimentar `DocumentInconsistencyEvaluator`
(la regla que compara IMEI del documento contra el bien asegurado).

**Lo que falta:** ese `Map<String, DocumentExtraction>` es **transitorio** — vive dentro de
`ClassificationOrchestrator.resolveClassification`/`extractAllAttachments`, se usa y se descarta
en la misma corrida. No hay entidad, no hay tabla, no se persiste en ningún lado, así que no hay
nada que `cases-service` pueda leer después.

**No estaba trackeado en ningún lado** — se buscó en el resto de `docs/` y lo más cercano es
H0023 (cruce de IMEI), que es una porción más chica, no la persistencia general. Se cargó como
card nueva, H0031.

**Antes de escribir el script de la tabla: esto va al DER primero.** El DER (`docs/arbiter der.mdj`)
es la fuente de verdad del modelo — no se acepta implementar una tabla que el DER no contempla y
dejarlo como desvío documentado (ver el CLAUDE.md de este repo, "El DER es fuente de verdad"). No
edité el `.mdj` — es un archivo de StarUML, se suma a mano en la herramienta —, pero dejo acá la
entidad exacta propuesta para que se cargue ahí primero y el script de `db/init-multitenant.sql`
la siga, espejo del patrón que ya usa `llm_analysis`/`llm_reason` (una tabla con los campos + una
tabla hija de una fila por hallazgo visual, en vez de un array serializado):

```sql
-- Una fila por documento adjunto ya leído por el modelo. FK 1:1 a case_documents (el documento
-- que ya persiste cases-service) — mismo patrón cross-módulo que ya usan llm_analysis/rule_result
-- contra cases.
CREATE TABLE document_extraction (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    transcription       TEXT         NOT NULL,
    document_date       DATE,                    -- null = el documento no trae fecha
    amount              NUMERIC(14,2),
    item_description    VARCHAR(255),
    imei                VARCHAR(20),
    affected_party      VARCHAR(20)  NOT NULL,    -- TITULAR | FAMILIAR | TERCERO | DESCONOCIDO
    extracted_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    case_document_id    BIGINT       NOT NULL REFERENCES case_documents(id) ON DELETE CASCADE,

    CONSTRAINT document_extraction_case_document_unique UNIQUE (case_document_id)
);

-- Señales visuales de manipulación, una fila por hallazgo (vacío es lo normal, no una ausencia).
CREATE TABLE document_visual_finding (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    finding         TEXT   NOT NULL,
    extraction_id   BIGINT NOT NULL REFERENCES document_extraction(id) ON DELETE CASCADE
);
```

Es una propuesta, no una decisión cerrada — Fede (o quien cargue el DER) puede ajustar
nombres/tipos, pero la forma general (dos tablas, no una con array serializado; FK a
`case_documents`, no a `cases`, porque el dato es por documento) sigue el mismo criterio que ya
está aplicado en el resto del esquema.

### Historia: tab "Conversación"

> Como analista, quiero ver los mensajes intercambiados con el asegurado sobre este expediente
> (pedidos de aclaración, avisos de estado), para tener el contexto completo sin salir de la
> pantalla ni ir a buscar mails sueltos.

**Lo que ya existe:** nada. Se buscó en todo el repo (`class \w*Conversation`, `class \w*Message`)
y no hay ninguna entidad de mensajería en el dominio — ni en `common-lib`, ni en `cases-service`,
ni en ningún otro módulo. Los únicos mensajes que salen del sistema hoy son notificaciones
unidireccionales por `SendGridAdapter` (cambios de estado), no una conversación con turnos.

**Lo que falta:** es una feature de punta a punta, sin nada previo:
1. Definir el dueño del dato (¿`cases-service`, ya que el expediente es su ciclo de vida? ¿un
   módulo nuevo?) y el modelo: una entidad `CaseMessage`/`Conversation` con autor, rol
   (asegurado/analista/sistema), texto, timestamp, y el expediente al que pertenece.
2. Decidir si es texto libre bidireccional (requiere que el portal del asegurado también tenga
   una UI de mensajería, no solo el analista) o algo más acotado (ej. "pedir aclaración" como una
   acción con plantilla, más cerca de una notificación que de un chat).
3. Endpoint(s) REST + el mismo patrón de `List<...>` en `CaseResponse` que las otras tabs, y el
   `hasConversation` correspondiente en el componente.

Es la más grande de las dos — probablemente no entra en el sprint actual. Vale la pena que Fede
la dimensione antes de comprometerla.

---

## Qué falta

1. **Commitear todo esto** — se organizó en grupos lógicos en sesiones anteriores de esta misma
   rama; seguir el mismo criterio (memoria/modelo/scheduler pueden ir juntos como "fix de
   infraestructura de clasificación por CPU"; el fix de `analysisReasons` — DER + tab "Razones",
   backend y frontend — es un commit aparte, ya cerrado, no queda nada pendiente de esa parte).
2. **`.env` de cualquiera que clone el repo** necesita `OLLAMA_MODEL=qwen3-vl:8b-instruct` a mano —
   no hay forma de que el compose lo fuerce si la env var ya existe con otro valor.
3. **`RulesRestAdapterTest`** no valida query params — es la razón por la que el bug #3 pasó
   desapercibido. No es bloqueante, pero es la clase de gap que se repite.
4. **Docs de test-case desactualizadas**: `docs/caso-prueba-reglas-duras.md` y la versión de
   Provincia no mencionan nada de esto — no hacía falta tocarlas para este handoff, pero si alguien
   arma un nuevo caso de prueba con documentos reales, la referencia de tiempos esperados
   ("segundos") ya no aplica corriendo por CPU.

---

# Respuesta de Fede (18/8) — trabajado sobre esta misma rama

Los tres puntos que dejaste para mí, con lo que se hizo. Todo commiteado acá; `mvn test` completo
en verde (**544 tests**) después de los cambios.

## 1 · Markdown en los factores del LLM — **confirmado y arreglado**

No hizo falta correr una clasificación nueva: ya había 88 motivos persistidos en `llm_reason` para
mirar. El resultado confirma tu hipótesis y la afina:

| | motivos | con `**` |
|---|---|---|
| `arbiter_bbva` | 39 | 0 |
| `arbiter_provincia` | 49 | **5** |

Las 5 son **todas del LLM** — largas, narrativas ("Esto sugiere que `**`no son documentos
reales`**`, lo cual es..."). Las que no tienen markdown son mensajes del **motor de reglas**, cortos
y con plantilla ("Falta documento requerido: police_report"). O sea que el modelo imita el estilo
del prompt solo cuando redacta libre: ~6% de los motivos, siempre para enfatizar.

Se hicieron **los dos** caminos que proponías, y no uno solo. Razón: el modelo ya cumple solo en el
94% de los casos sin que nadie se lo pida, así que una línea en el prompt probablemente lo lleve a
~100% — pero "probablemente" no alcanza para una tabla auditable (Disposición 2/2023).

- **Prompt** (`classification-v4.md`, sección "Tarea de clasificación"): una línea pidiendo texto
  plano, explicando que la pantalla del analista no interpreta formato.
- **Saneo** en `OllamaClaimClassifier.parseResponse` → `plainText(...)`. **Solo asteriscos.**
  Sacar guiones bajos habría roto contenido real: los factores traen `police_report` y
  `last_connection`. `output_raw` sigue guardando la respuesta del modelo verbatim.
- **Tests nuevos** (`OllamaClaimClassifierTest`, no existía): uno fija que se limpie el `**`, otro
  que **no** se toquen los guiones bajos.

**Lo que no se pudo verificar en vivo:** el fix aplica a clasificaciones **nuevas**, y una corrida
por CPU lleva 1h+, así que las 88 filas viejas siguen con su texto original. Si querés dejar la
base limpia, es un `UPDATE ... replace(reason, '**', '')` sobre los dos esquemas — no se hizo para
no tocar datos sin avisar.

> **Actualización (18/8, más tarde):** se corrió sobre Railway, con autorización explícita del
> usuario. Se verificó primero con `SELECT` que eran exactamente esas 5 filas (todas del caso 16,
> `arbiter_provincia.llm_reason.id IN (39,42,43,45,48)` — coincide con lo que reportaste), después
> `UPDATE arbiter_provincia.llm_reason SET reason = replace(reason, '**', '') WHERE reason LIKE
> '%**%'` (acotado a `arbiter_provincia`, que era donde estaban las 5 — `arbiter_bbva` seguía en 0).
> `UPDATE 5`, verificado que no queda ninguna fila con `**` en ese esquema, y las 5 leen bien (sin
> dobles espacios ni cortes raros donde estaba el `**`). No se tocó `arbiter_bbva` ni ningún otro
> dato. Este punto queda cerrado.

### Tu punto 4 — `DocumentExtraction`: revisado, **no es un problema**

`transcription` y `visualFindings` **nunca llegan a la pantalla**: cero referencias en todo el
frontend. No se persisten como texto visible — alimentan el prompt de clasificación
(`attachmentsOcr`) y el `RiskContext` del scoring. Markdown ahí no se le muestra a nadie.

También se revisó `foldForensicTraces`, que es el otro camino por el que entra texto a `factors`
**después** del saneo del parser: arma los rastros con `String.format` sobre datos estructurados
(nombre de archivo, % de similitud, URLs), no con prosa del modelo. Sin riesgo.

Queda un efecto de segundo orden, anotado por si alguna vez molesta: la transcripción entra al
prompt de clasificación, así que si trae `**`, el modelo ve Markdown en su propio input y es más
probable que lo imite. Es marginal comparado con la causa principal — el prompt de clasificación
tiene 19 marcas de negrita propias contra 9 del de extracción — así que **no se tocó**.

## 2 · Card "Sugerencia del sistema" — **confirmada muerta y eliminada**

Tu lectura era correcta y se verificó por dos lados: en el HTML es un `app-empty-state`
incondicional, sin ningún `@if`; y en el `.ts` no hay una sola mención a "sugerencia"/"suggestion",
o sea que no hay binding ni campo que la alimente.

Antes de sacarla se chequeó si había algo útil para cablearle: se cruzaron **los 9 campos** que
expone `ExpedienteResponse` (`forensicReport`, `riskBreakdown`, `analysisReasons`,
`analysisConfidence`, `analysisClassification`, `pep`, `riskScore`, `riskBand`, `statusHistory`)
contra lo que renderiza la pantalla — **todos ya están bindeados en algún lado**. No quedaba dato
huérfano que mostrar, así que no había nada que cablear.

Se eliminó. Sobre tu punto 2: `.col-side` es un `flex column` con `gap`, así que con una sola card
("Analista asignado") no hizo falta tocar el SCSS — verificado en vivo en el expediente #17.
`EmptyStateComponent` sigue importado porque lo usan otras cinco partes del template.

## Dos hallazgos NUEVOS sobre tu scheduler (18/8, corriendo el caso 29)

Salieron de mirar `case_status_history` del expediente 29 mientras se intentaba la verificación en
vivo. **Los dos son de tu bug #4, no los tocamos.**

### A · ~~El bloque `classification-refresh` no se bindea~~ — **FALSA ALARMA, tu fix anda**

> **Cerrado el 18/8, más tarde.** Se agregó un `@PostConstruct` que loguea la ventana efectiva y
> dice: `[Refresh] Ventana: interval-ms=20000 x max-attempts=540 = 180 min`. **El binding funciona
> y tu config se aplica.** El log quedó en el código, que igual sirve para no volver a deducir la
> ventana desde afuera.
>
> Lo más probable es que las fallas de "121 reintentos" salieran de una **imagen anterior a tu fix**:
> al arrancar Docker Desktop se relevantó solo el stack con imágenes de 3 días atrás, y el jar se
> inspeccionó recién más tarde, ya con la imagen nueva — o sea que se sacó una conclusión sobre
> fallas viejas mirando un binario distinto. **No hay nada que arreglar acá.**
>
> Lo que sigue abajo se deja como registro de lo que se descartó, por si el síntoma reaparece.

### El análisis original (ya no aplica)

El caso falló tres veces con `"clasificación fallida tras 121 reintentos"` / `"tras 120"`, y 120 es
el default del código (`@Value("${arbiter.classification-refresh.max-attempts:120}")`).

**No es solo `max-attempts`: también se ignora `interval-ms`.** La aritmética lo prueba sin
necesidad de leer la propiedad — el reintento manual fue 21:16:52 y falló 21:35:36 tras 121
intentos: **9,4 s por intento**. Con `interval-ms: 20000`, 120 intentos serían 48 minutos, no 19.
Y 9,4 s ≈ los 5000 ms del default más lo que tarda cada barrido. Que fallen las dos juntas descarta
un typo en una clave: no se bindea el bloque.

Hipótesis descartadas, cada una con evidencia (para no repetir el camino):

| | resultado |
|---|---|
| El jar corre una versión vieja del yml | ❌ se extrajo `BOOT-INF/classes/application.yml` del contenedor: dice 540 |
| Clave `arbiter:` duplicada (en YAML la segunda pisa a la primera, sin avisar) | ❌ una sola, línea 41 |
| Varios documentos YAML separados por `---` | ❌ ninguno |
| Tabs o indentación rota | ❌ `cat -A`: 4 espacios, sin tabs |
| Otro `application.yml` en el classpath | ❌ uno solo dentro del jar |
| `application-insurer-db.yml` (el perfil activo) | ❌ no existe |
| `SPRING_CONFIG_LOCATION` u otra env pisando | ❌ solo `SPRING_PROFILES_ACTIVE=insurer-db` |
| Volumen montado sobre la config | ❌ el contenedor no tiene mounts |

Todo lo estático está impecable. **No se pudo determinar la causa desde afuera del proceso**:
`/actuator/env` responde 401 y no se llegó a mirar el valor efectivo en runtime.

**Próximo paso sugerido:** un `log.info` de `maxAttempts` en un `@PostConstruct` del scheduler. Si
arranca en 120, el binding nunca ocurrió y hay que mirar cómo se crea el bean; si dice 540 y aun así
falla a los 121, el problema está en el contador y no en la config.

### B · Cada transición se registra dos veces

```
21:35:36  PENDING_CLASSIFICATION → CLASSIFICATION_FAILED  SYSTEM  tras 121 reintentos
21:35:38  PENDING_CLASSIFICATION → CLASSIFICATION_FAILED  SYSTEM  tras 121 reintentos
```

Pasó las **tres** veces que falló (20:35, 21:14, 21:35), siempre con 2-3 s de diferencia.

**Mecanismo probable.** Las dos filas están a 2 s, y con `fixedDelay` de 5 s dos ticks no pueden
estar tan cerca: **no son dos barridos, es uno solo**. Adentro de un barrido:

```java
for (Insurer insurer : insurerRepository.findByActiveTrue()) {
    TenantContext.set(insurer.getSchemaName());
    refreshPendingCasesForCurrentTenant();
}
```

Dos aseguradoras activas (BBVA y Provincia, esquemas distintos — se verificó en
`arbiter_common.insurer`) = dos iteraciones separadas por lo que tarda cada una, ~2 s. Si en la
segunda el `TenantContext.set()` **no alcanza a cambiar el esquema de la conexión ya abierta**,
vuelve a consultar BBVA, encuentra el caso todavía en `PENDING_CLASSIFICATION` —la transición
anterior puede no haber commiteado— y lo marca fallido de nuevo.

Encaja con las tres piezas observadas: los 2 s, que ambas filas caigan en `arbiter_bbva`, y que haya
pasado las tres veces. Y es coherente con que `cases-service` evita transacciones explícitas
justamente por el cambio de tenant.

**Cómo confirmarlo:** un `log.info` de `TenantContext.get()` al entrar a
`refreshPendingCasesForCurrentTenant()`. Si en las dos iteraciones imprime `arbiter_bbva`, está
confirmado.

Ensucia el historial que ve el analista y la auditoría del expediente.

## Sobre correr esto en la máquina de Fede — no es viable

Se intentó la verificación en vivo del Markdown y se abandonó. Para que no se repita el intento:

| | máquina de Aylén | la de Fede |
|---|---|---|
| RAM física | 32 GB | **16 GB** |
| WSL2 | 24 GB | 13 GB (era 8 por default, se subió con `.wslconfig`) |
| Lectura de 1 PDF | ~40 s | **~12 min** |

Con los `mem_limit` del repo (20.75 GB) el kernel mataba `llama-server` igual que en tu bug #1. Se
bajaron a ~11.75 GB en un `docker-compose.override.yml` local (gitignoreado, no afecta a nadie) y
ahí dejó de morir, pero a 12 min por documento la corrida completa no cierra: el caso 29 llevaba 45
min y seguía en el cuarto adjunto.

**Detalle que costó encontrar:** `docker-compose.override.yml` **no se carga** si se pasa
`-f docker-compose.railway.yml` explícito. Hay que nombrarlo:
`docker compose -f docker-compose.railway.yml -f docker-compose.override.yml up -d`.

**Pedido concreto:** que la corrida de verificación del prompt la hagas vos en tu máquina.

## 3 · Las dos historias — sin decidir todavía

"Datos extraídos" y "Conversación" quedan como las dejaste. Falta que Fede confirme el alcance,
sobre todo si la extracción OCR + matching contra póliza entra en este sprint. Coincidimos en que
"Conversación" es de otro tamaño: sin entidad de mensajería, es de punta a punta.

---

# H0031 "Datos extraídos" — implementada (18/8, Aylén)

Se hizo de punta a punta. El dato que `classification-service` venía calculando y tirando en cada
corrida ahora se persiste, viaja y se ve.

**Nombre de las tablas: `document_analysis` + `document_visual_finding`**, no `document_extraction`
como decía mi propuesta. Dos razones: `DocumentExtraction` ya es el DTO transitorio y tener una
entidad homónima obliga a calificar el paquete en cada archivo que use las dos; y `*_analysis` es
la familia que ya existe (`llm_analysis`, `risk_analysis`, `image_analysis`), producida acá por el
`DocumentAnalyzer`. La forma general no cambió: dos tablas, FK a `case_documents` (el dato es por
documento, no por expediente), hallazgos visuales en filas y no en un array serializado.

| Paso | Estado |
|---|---|
| Tablas en `init-multitenant.sql` + migración idempotente | ✅ `db/migrations/2026-08-18-datos-extraidos.sql` |
| Migración aplicada a Railway | ✅ verificado: las 2 tablas en los 2 esquemas de tenant |
| Entidades + repositorio en classification-service | ✅ `DocumentAnalysis`, `DocumentVisualFinding` |
| Persistencia en el flujo real | ✅ `ClassificationOrchestrator.recordDocumentExtractions` |
| Lectura + `CaseResponse.documentAnalyses` | ✅ `CaseDocumentAnalysisRepository` |
| Tab "Datos extraídos" de vuelta en pantalla | ✅ condicional, igual que "Razones" |
| **Sumar las 2 entidades al DER** | ❌ **queda para vos** — es StarUML, a mano |

**Decisiones que conviene conocer antes de tocarlo:**

- **Se lee por SQL, no por REST.** La historia decía "endpoint REST interno", pero el precedente
  del repo para exactamente esto es `CaseAnalysisRepository`, que documenta por qué: las tablas
  viven en el **mismo esquema de tenant** que `cases`, así que es un query y no un salto HTTP.
  Mismo idiom (JDBC plano sobre la conexión de Hibernate, para que el `search_path` multi-tenant
  aplique). `cases-service` sólo lee; el dueño sigue siendo classification-service.
- **Sólo en el detalle, nunca en listados.** `documentAnalyses` se puebla en `GET /cases/{id}` y va
  vacía en la bandeja, igual que `statusHistory`. A propósito no existe un `findByCaseIds`: si
  estuviera, alguien lo llamaría desde el listado y sería un join por fila.
- **Una fila por documento, se pisa al reclasificar** (`UNIQUE (case_document_id)`), a diferencia de
  `llm_analysis` que es append-only. Lo que audita la Disposición 2/2023 es la recomendación y sus
  motivos; esto es la lectura de apoyo, y guardar cada extracción del mismo PDF sin cambios sería
  ruido. Por eso el repo borra las anteriores antes de escribir — con `@Transactional`, porque un
  derived delete no lo es solo y explota con `TransactionRequiredException`.
- **Best-effort**, como el policy snapshot: si falla el guardado se loguea error y la clasificación
  sigue. Un dato de apoyo que no escribe no puede voltear una clasificación que un analista espera.
- **`null` es "el documento no lo dice", NUNCA "no coincide".** Está sostenido en las 3 capas: la
  columna es nullable, el DTO lo documenta, y la pantalla lo muestra como "No aplica". Tratar un
  campo ausente como discrepancia sería acusar al asegurado por algo que nadie declaró.
- **Los expedientes ya clasificados no tienen datos** y la tab no les aparece: la extracción vieja
  nunca se guardó y no hay de dónde sacarla. Se llena en la próxima clasificación de cada uno.

**Lo que NO se hizo, y por qué:** el criterio de aceptación "marcar discrepancia contra la póliza"
quedó afuera. Depende de H0023 — el IMEI del bien asegurado vive sólo en `aseguradora_*.poliza.imei`
y no está en la `policy` local, así que no hay contra qué comparar del lado de Arbiter. Hoy la
discrepancia ya se ve, pero por otro lado: `DocumentInconsistencyEvaluator` la levanta como factor
del score y sale en la solapa "Desglose de riesgo". Con H0023 cerrada, marcarla también acá es
sumar la comparación al mapeo, no rehacer nada.
