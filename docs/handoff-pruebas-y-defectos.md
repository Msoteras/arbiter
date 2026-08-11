# Handoff — Plan de pruebas: alcance real, defectos y qué falta

**Fecha:** 09/08/2026 · **De:** Fede · **Para:** Aylén
**Rama revisada:** `feature/front-details`, commit `0ed76ec` (working tree limpio)
**Disparador:** card de Sprint 7 *"Caso de Prueba - BBVA en Ramo Tecnología Portátil"* (Fast Track
Express / Doc Reducida / Fraudulento con constancia IMEI falsa).

**Cómo se hizo:** lectura de código sobre esa rama. **No se levantó Docker ni se corrió nada contra
Railway en esta sesión** — todo lo que sigue sale del código y de los scripts de `db/`, no de la base
viva. Donde eso importa está marcado.

---

## 1 · Qué se revisó y qué no

| Área | Revisada | Comentario |
|------|----------|------------|
| Alta de denuncia (wizard del asegurado) | ✅ | Componente + `CaseRequest` + resolución de referencias |
| Clasificación (Fast Track, LLM, documentación) | ✅ | Orquestador, validador, adapters de reglas, prompt |
| Scoring de fraude / análisis forense de imágenes | ✅ | Evaluadores + cascada CLIP/pgvector → Vision |
| Motor de reglas (lo que configura el referente) | ✅ | Fast Track, agenda documental, textos, scoring |
| Expedientes: estados, decisión del analista, documentos | ✅ | Transiciones + endpoints + control de acceso |
| Portal del asegurado | ✅ (superficial) | Confirmado que pega a endpoints reales, no mock |
| Reportes y Dashboard del referente | ✅ | Ver §5: no hay backend |
| Auth / alta de usuarios / Auth0 | ❌ | No se tocó en esta pasada |
| Frontend responsive / accesibilidad | ❌ | No se tocó en esta pasada |

---

## 2 · El caso del card: BBVA en Ramo Tecnología Portátil

### 2.1 Punto de partida: BBVA hoy no vende ese ramo

En los scripts versionados, Tecnología Portátil es **de Provincia**:

- `db/seed-demo.sql:112` — la única póliza `'Tecnología Portátil'` está en `aseguradora_provincia`.
  Las 5 de BBVA son todas Celulares.
- `db/init-multitenant.sql:693` — cada tenant arranca con coverages 1 y 2, ambas `branch_id = 1`.
  La cobertura de branch 2 (`Daño accidental`) se la agrega `seed-demo.sql:279` **solo a Provincia**.
- Igual el panel de ramos le muestra "Tecnología Portátil · 2 coberturas" a BBVA, porque la lista sale
  de un mock del front (`arbiter-frontend/src/app/features/admin/rules-config.service.ts:100`).
  La solapa abre vacía. Es lo que quedaba del P3 del handoff de reglas.

**Mínimo para que el caso exista** (5 cosas, todas de datos, ninguna de código):

1. `aseguradora_bbva.poliza` con `rama = 'Tecnología Portátil'` + su `cobertura` con suma asegurada
   (de ahí salen `branch`, `product` e `insuredAmount`).
2. `arbiter_bbva.coverage` con `branch_id = 2`.
3. `arbiter_bbva.policy` apuntando a esa coverage — **el expediente hereda la cobertura de la póliza**,
   no la elige el asegurado (`CaseServiceImpl.java:99`).
4. `arbiter_bbva.insured` con el DNI de quien va a denunciar (Martina o Julián).
5. Config del referente para esa cobertura (Fast Track y textos): el mock no trae entrada para
   coberturas nuevas.

> ⚠️ Mar re-seedeó Railway y Tecnología pasó de branch 3 a **branch 2**. Antes de escribir ids
> concretos en los casos de prueba, confirmar el catálogo real contra Railway.

### 2.2 Límites duros del ramo (aplican a todos los casos)

- **Hecho generador: solo Robo o Hurto.** `CaseReferenceResolver.java:34` resuelve `(ramo, nombre)` y
  tira **422** si no existe. Branch 2 tiene `Daño accidental`, `Robo en vía pública` y `Hurto`; el
  wizard ofrece 4 tipos fijos (`nueva-denuncia.component.ts:133`): Robo ✅, Hurto ✅,
  **Rotura accidental ❌**, **Otro → "Siniestro general" ❌**. O sea: *Daño accidental*, la cobertura
  propia del ramo, **no es alcanzable desde la UI**. → **Resuelto (`7c8dd20`)**: el wizard ahora
  ofrece los hechos generadores reales del ramo desde el backend (falta validar en vivo).
- **No hay slot para la constancia de baja de IMEI.** El asegurado sube 4 tipos
  (`case-document.ts:26`): `police_report`, `item_photo`, `invoice`, `quote`. La agenda documental
  del referente maneja otros 4 (`business-rules.ts:112`): `police_report`, `purchase_proof`,
  `imei_deregistration`, `last_connection`. **Solo `police_report` intersecta.** → **Resuelto
  (`14fce00`)**: vocabulario unificado (el uploader deriva de la agenda del referente),
  `imei_deregistration` ahora es subible (falta validar en vivo).
- **Cierre**: todos los casos terminan en `APPROVED` o `REJECTED` vía el endpoint de decisión
  (`APROBAR`/`RECHAZAR`, `CaseServiceImpl.java:376`), que solo acepta casos en
  `PENDING_ANALYST_REVIEW`.

### 2.3 Decisiones de alcance ya tomadas (Fede, 09/08)

- **"Doc Reducida" no es un concepto nuevo**: es el camino `FALTA_DOCUMENTACION` con
  `AWAITING_DOCUMENTATION` como estado intermedio, y **no es específico de este ramo**.
- **Los dos casos de fraude se diseñan**, el que no está implementado va en rojo con su defecto.
- **El alcance llega hasta la decisión del analista** (no hasta el mail: no existe, ver §5).

### 2.4 Los 6 casos, delimitados

| # | Caso | Recorrido | Estado |
|---|------|-----------|--------|
| 1 | **Fast Track (Express)** | Denuncia → gate determinístico → `PENDING_ANALYST_REVIEW` con `FAST_TRACK` → analista aprueba → `APPROVED` | ✅ Verde **si** el referente habilita Fast Track para esa cobertura. Que ande solo con configuración es, además, el caso de prueba del RNF de mantenibilidad ("ramo nuevo en ≤1 sprint sin tocar código"). |
| 2 | **Falta documentación** | Denuncia incompleta → `AWAITING_DOCUMENTATION` → el asegurado sube → `PENDING_CLASSIFICATION` → reclasifica → analista decide | ✅ El gate ya lee la agenda real del referente (`14fce00`); falta validar en vivo. |
| 3 | **Fraude: adjunto reusado / bajado de la web** | Igual que 1, pero el adjunto ya se usó en otra denuncia → `image_reuse` (o `image_web_match`) suma al score → analista rechaza | ✅ Verde de punta a punta, con informe forense visible. |
| 4 | **Fraude: constancia IMEI fabricada** | Constancia inventada, no reusada, IMEI que no coincide | ✅ **Pasó a verde (10/08)**, sin validar en vivo. El **cruce de IMEI** ya se evalúa (D4b: `poliza.imei` + campos tipados en la extracción) y suma al factor `document_inconsistency`; además la extracción reporta **indicios visuales** de fabricación (D5). Para que el caso corra, la constancia de prueba tiene que traer un IMEI **distinto** al de la póliza (`351000000000042` en BBVA POL-CEL-2026-042). |
| 5 | **Factura de otro equipo** | Factura de un celular distinto al bien asegurado | 🟡 Solo el LLM puede notarlo leyendo el OCR; no es determinístico. El resultado esperado no puede ser "el sistema detecta", sino "el analista tiene el dato a la vista". |
| 6 | **Hurto no cubierto** | Denuncia de Hurto sobre una cobertura que lo excluye | ✅ Ahora el motor lo detecta por **regla** (`COVERAGE_EXCLUSION`, D3): bloquea Fast Track, deriva a revisión manual sin LLM y deja fila en `rule_result`. El analista sigue decidiendo el cierre (no auto-`REJECTED`). Falta validar en vivo. |

---

## 3 · Defectos y gaps encontrados

Severidad según los criterios del plan de pruebas (Crítico / Alto / Medio / Bajo).

### 🔴 Críticos

**D1 · Cualquier asegurado puede subir documentos al expediente de otro** — ✅ **RESUELTO (Aylén, 09/08)**
`POST /cases/{id}/documents` era `hasAnyRole('ASEGURADO','REFERENTE_ASEGURADORA')`, y
`addDocumentsAndReclassify` hacía `findById` y nada más: **no pasaba por `CaseAccessPolicy`**. Los
*reads* sí estaban cerrados (`readableCase`), este *write* no. Impacto: subir documentación a un
expediente ajeno y forzarle una reclasificación.
- **Fix**: la carga pasa por `readableCase(caseId)`, el mismo control de pertenencia que las
  lecturas → 404 (no 403) sobre un expediente ajeno, por la razón que documenta `CaseAccessPolicy`
  (los ids son secuenciales).
- **Test**: `CaseServiceImplTest.addDocumentsAndReclassify_someoneElsesCase_isRejected` — verifica
  además que no se guarde documento ni se reencole clasificación.

**D2 · El alta de denuncia no valida a nombre de quién se denuncia** — ✅ **RESUELTO (Aylén, 09/08)**
En `createCase`, `insuredId` (DNI) y `policyNumber` salían del payload y se resolvían por separado
(`CaseReferenceResolver`), sin compararse contra el usuario del JWT **ni entre sí**. Se podía
denunciar sobre la póliza de otro, o combinar el DNI de uno con la póliza de otro.
- **Fix, dos chequeos**: (1) el `insuredId` del payload tiene que ser el DNI del token
  (`CallerContext`) → `InsuredIdentityMismatchException`, **403**; se valida, no se sobreescribe en
  silencio, para que un front que manda mal el campo falle fuerte. (2) `policy.insuredId` tiene que
  ser el id del asegurado resuelto → `PolicyInsuredMismatchException`, **422**; con `Objects.equals`,
  así una póliza sin dueño sincronizado tampoco pasa (dueño que no se puede verificar = no se
  acepta).
- **Tests**: `createCase_denunciaOnBehalfOfAnotherInsured_isRejected`,
  `createCase_callerWithoutDni_isRejected`, `createCase_policyBelongingToAnotherInsured_isRejected`,
  `createCase_policyOfTheInsuredFiling_goesThrough`.

**D20 · Los endpoints del asegurado estaban habilitados para el referente** — ✅ **RESUELTO (Aylén, 09/08)**
*Detectado al arreglar D1/D2.* `POST /cases` y `POST /cases/{id}/documents` estaban en
`hasAnyRole('ASEGURADO','REFERENTE_ASEGURADORA')`, más laxo que la regla de negocio: **denunciar y
subir documentación son del asegurado y de nadie más**. El frontend ya lo aplicaba (las rutas
`new-claim` y `portal/cases/:id/documentacion` están en `roles: ['ASEGURADO']`), así que era el
backend el que quedaba abierto — no había forma de llegar desde la UI, pero sí con un token de
referente y Postman.
- **Fix**: los dos endpoints pasaron a `hasRole('ASEGURADO')`.
- **Tests**: `CaseSecurityTest.createCase_asReferente_returns403`,
  `uploadDocuments_asReferente_returns403`, `uploadDocuments_asAnalista_returns403`.
- **Ojo si aparece el alta de asegurados por el referente** (CLAUDE.md decisión #8): ese flujo no
  existe hoy, pero si algún día el referente carga denuncias por teléfono o mesa de entradas, esto
  se reabre como decisión de negocio, no como defecto.

### 🟠 Altos

**D3 · Nada valida que el hecho generador esté cubierto por la cobertura** — 🟠 **RESUELTO (backend, 10/08)**, sin validar en vivo
El expediente hereda la cobertura de la póliza (`CaseServiceImpl.java:99`) y no había chequeo alguno.
Denunciar Hurto sobre una cobertura que excluye el hurto entraba igual y llegaba a la bandeja.
- **Fix**: se implementó el plan [plan-reglas-evaluables.md](plan-reglas-evaluables.md) en su opción (a).
  Una regla `COVERAGE_EXCLUSION` en `insurer_rule` (JSONB = lista negra de `claim_cause`) define qué
  hechos generadores NO cubre una cobertura. rules-service la sirve por `/internal/evaluable`,
  `RulesRestAdapter` la overlaya, y `CoverageRuleEvaluator` (classification) la evalúa **por id**
  (`ClaimReport` ahora lleva `claimCauseId`) **antes** del gate de Fast Track. Una exclusión bloquea
  el Fast Track y deriva a `LLM_SOLICITA_REVISION_MANUAL` sin llamar al LLM — no cierra el expediente
  (human-in-the-loop, CLAUDE.md #5). Lista negra a propósito: una cobertura sin regla se comporta
  igual que hoy.
- **Seed**: `init-multitenant.sql` siembra la exclusión del caso 6 (cobertura de robo excluye Hurto).
  ⚠️ confirmar ids contra Railway (§8) antes de fijarlos en los casos de prueba.
- **Tests**: `CoverageRuleEvaluatorTest` (unit puro) + caso 6 en `ClassificationOrchestratorIntegrationTest`.

**D4 · Las exclusiones y reglas de negocio son texto, no reglas — y no queda auditoría**
- **D4a**: tanto las **exclusiones** (`rules.exclusions()`) como las **reglas de negocio en texto**
  (`rules.rules()`) que escribe el referente se pegan como texto en el **prompt del LLM**
  (`PromptBuilder.renderRulesAndPolicy`; `RulesRestAdapter` las superpone desde la DB) y ahí termina
  su vida: el único que las "aplica" es el modelo, interpretándolas — **no hay código que las
  evalúe**. Consecuencia: **no determinístico** (la misma denuncia puede clasificar distinto entre
  corridas) y **sin auditoría** de qué regla se evaluó y con qué resultado. Contradice la implicancia
  #4 del `CLAUDE.md` (las exclusiones de cobertura son **reglas evaluables en rules-service**, no
  decisiones del LLM).
  - **Matiz**: muchas son *duras-eables* y deberían evaluarse en código — plazo de denuncia (fechas),
    póliza al día (estado de pago), monto dentro del límite (comparación), "hurto sin violencia"
    (exclusión dura). Solo las genuinamente *interpretativas* (relato inconsistente, daño no
    relacionado con el robo) justifican quedar en el prompt. **Diseño objetivo**: evaluar las duras
    en el motor + escribir `rule_result` (ver §7.1), y dejar al LLM solo las interpretativas.
  - **RESUELTO (10/08)**: las reglas duras (exclusión de cobertura D3, y temporales D10/D11/D13) ya
    se evalúan por código. **Paso 6 hecho**: el prompt se bumpeó a **`classification-v3`** e inyecta
    el **veredicto determinístico del motor** ("reglas duras ya evaluadas — no re-decidir"), con una
    instrucción explícita para que el LLM no las reinterprete y se enfoque en el relato/consistencia.
    De paso se dejó consistente `llm_analysis.prompt_version` (antes el template era v2 pero se
    persistía `classification-v1`). Lo que **queda en el prompt** son las reglas genuinamente
    *interpretativas* (relato inconsistente, daño no relacionado), que es lo correcto.
- **D4b**: `DocumentInconsistencyEvaluator` —el factor que agarraría "el IMEI del documento no
  coincide con el del bien"— es un **stub** que se declara no evaluable. *Reencuadrado el 10/08:*
  - **Qué debería cruzar**: datos **entre** los documentos y contra el siniestro. La factura dice
    IMEI `358…` y el bien asegurado tiene `359…`. La constancia policial fecha el robo el 5 y la
    denuncia dice el 3. La factura es de un Samsung y el bien es un Motorola.
  - **Por qué hoy no puede**: el OCR devuelve **texto libre**, un bloque por documento. Comparar "el
    IMEI de la factura" con "el IMEI del bien" exige el IMEI como **campo tipado**; comparar dos
    párrafos de prosa no da un resultado determinístico, y este factor tiene que serlo.
  - **No es "esperar a H0007" en abstracto**: es exactamente la misma forma que
    `covers_family_group` (ver la nota de diseño de D9) — una pasada de **extracción** que devuelve
    hechos tipados, y **código** que compara. El contrato de extracción ya existe desde D5
    (`DocumentExtraction`): hoy devuelve `transcription` + `visualFindings`, y lo que falta es que
    devuelva además los campos estructurados (IMEI, fecha del documento, monto, modelo). Las dos
    historias se destraban con el mismo paso.
  - **La consecuencia que lo hacía defecto y no tarea pendiente**: el referente podía activar el
    factor y ponerle peso, y no aportaba nada nunca.
  - ✅ **RESUELTO (10/08)**, sin validar en vivo. Se hizo lo que decía el reencuadre:
    - **El operando que faltaba**: el IMEI del bien **no existía en el modelo**. `poliza.bien_asegurado`
      es un `VARCHAR(255)` con el modelo comercial, y el IMEI solo aparecía **hardcodeado dentro del
      string** en los mocks y los tests. Se agregó `poliza.imei` a la BD Aseguradora simulada (no es
      cambio de DER: ese esquema representa el sistema externo, no el modelo de Arbiter), con valores
      en el seed **solo para Celulares** — en Tecnología Portátil queda NULL, una notebook no tiene.
      Viaja a `InsuredPolicy.imei` por el `InsurerDatabaseAdapter`.
    - **Campos tipados en la extracción**: `DocumentExtraction.Fields` (`documentDate`, `amount`,
      `itemDescription`, `imei`) y prompt **`extraccion-documento-v3`**. El IMEI se normaliza a
      dígitos y la fecha se parsea; lo que no se puede interpretar queda en null y no rompe nada.
    - **Las extracciones llegan al scoring**: `RiskContext` las lleva indexadas por tipo de documento.
      Se reusa la pasada que ya corrió para el prompt — no se paga una segunda.
    - **El evaluador compara tres cosas**: IMEI del documento vs. el del bien; fecha del documento vs.
      fecha del hecho (con 7 días de tolerancia, porque la factura de compra es legítimamente
      anterior); e importe del documento vs. monto reclamado (10% de tolerancia por IVA y redondeos).
    - **Regla central: un campo ausente NUNCA es una inconsistencia.** Una constancia policial no trae
      IMEI y una foto del equipo no trae monto; confundir "el documento no lo dice" con "no coincide"
      convertiría cada adjunto incompleto en una sospecha de fraude sobre una persona real. Sin
      documentos analizados el factor se declara **no evaluable**, no 0.0.
    - **Tests**: `DocumentInconsistencyEvaluatorTest` (10).
  - **Queda afuera**: comparar `itemDescription` contra el bien asegurado. Son dos strings libres
    ("Samsung Galaxy A56" vs "SAMSUNG A56 128GB NEGRO") y la comparación sería difusa, justo lo que
    este factor no puede ser. Se extrae igual, para cuando haya con qué compararlo.
- **D4c**: `rule_result` —la tabla donde se auditaría qué regla se evaluó y con qué resultado— tenía
  entidad y repository y **cero escritores**. → **RESUELTO (10/08)**: `ClassificationResultsService`
  escribe una fila por regla evaluada (PASS y FAIL, no solo rechazos) con `rule_id` → `insurer_rule`,
  en el flujo con `caseId`. Cierra el requisito de auditoría de la Disposición SSN 2/2023 para las
  exclusiones de cobertura. Falta que se sumen las demás reglas duras (D10–D13) a medida que se
  implementen.

**D5 · La clasificación del LLM es texto puro: no recibe la imagen** — *fecha/monto/lugar RESUELTO (Mar, v2)*
- ✅ **Resuelto**: la **fecha del hecho, el lugar y el monto reclamado** ya viajan al prompt. Se
  agregaron a `ClassificationRequest`, se mapean en `ClassificationOrchestrator.buildRequest()` desde
  el `ClaimReport`, y se imprimen en el template — renombrado a **`classification-v2.md`** con
  `getPromptVersion()` = `"classification-v2"` para no romper la auditoría de
  `llm_analysis.prompt_version` (Disposición SSN 2/2023). Implementado, **sin validar en vivo**.
- 🟠 **Cerrado de otra forma (10/08)**. La parte que quedaba decía que el modelo de **clasificación
  no recibe la imagen**, solo su OCR. **Mandársela se descartó**, y no es una postergación: choca con
  dos decisiones cerradas del `CLAUDE.md`. La **#2** fija la ventana en 32.768 tokens con prompts
  esperados de 6–15k, y una imagen de Qwen3-VL se lleva miles — con el agravante de que Ollama
  descarta el excedente **en silencio** (el propio `OllamaClaimClassifier` ya loguea ese warning).
  La **#11** dice explícitamente que la comparación de imágenes no se delega al modelo de visión:
  va por CLIP + pgvector (reuso interno) y Google Vision (match web), que es el pipeline de fraude
  y ya anda de punta a punta (caso 3). O sea: la clasificación seguirá siendo texto — es diseño, no
  deuda. Ver §5.
- ✅ **Pero la brecha que D5 señalaba sí se cerró, del lado barato.** El que tiene la imagen delante
  es el paso de **extracción** (`OllamaDocumentAnalyzer`), y su prompt solo pedía transcribir: ahí
  se perdía toda señal visual. Ahora (`extraccion-documento-v2`) devuelve **salida estructurada**
  `{ transcription, visualFindings[] }` — lo que el documento *dice* y lo que la imagen *aparenta*
  (tipografías que no coinciden, texto pegado, sellos deformados). Cuesta **cero contexto** en la
  clasificación: la pasada de visión ocurre igual.
  - **Van separadas a propósito**: mezcladas, "la firma está pixelada" se leería como contenido del
    documento. El prompt de clasificación (**`classification-v4`**) las presenta bajo su propio
    encabezado y le dice cómo pesarlas: no son concluyentes, y su ausencia no prueba autenticidad.
  - **El gate documental recibe solo la transcripción**: las señales visuales son interpretativas y
    no pueden decidir un Fast Track (lección de D4a).
  - **El prompt de extracción está escrito para no inventar sospechas**: lista vacía es el resultado
    esperado, se excluyen explícitamente foto torcida / mala luz / papel arrugado, y se exige la
    observación concreta. Es una señal que se le muestra a un analista sobre el reclamo de una
    persona real.
  - **De paso**: `PromptBuilder` ahora carga `prompts/<arbiter.ollama.prompt-version>.md`. Eran dos
    constantes independientes (el classpath en el código y la versión en el yml) y ya se habían
    desincronizado una vez — el template era v2 y se auditaba como v1. Ahora no pueden discrepar.
  - **Tests**: `OllamaDocumentAnalyzerTest` (5), `PromptBuilderTest` (+3).
  - **Queda**: esto le da materia prima a **D4b** (`DocumentInconsistencyEvaluator`), pero no lo
    cierra — el cruce "el IMEI del documento no coincide con el del bien" necesita extracción
    estructurada de campos (H0007), que es otra cosa que una señal visual.

**D19 · El `InsurerDatabaseAdapter` no se migró a multi-tenant** — 🟠 **RESUELTO (10/08, backend)**, sin validar en vivo
*Detectado 10/08 (Mar).* Los **dos** adapters (cases-service y classification-service) leían el
schema **bare `aseguradora`**, que es del modelo single-schema viejo (`db/datos-aseguradoras.sql`),
**no** los `aseguradora_<tenant>` que crea el seed multi-tenant (`init-multitenant.sql` →
`create_insurer_db_schema('aseguradora_bbva'…)`). Encima la query de cases hacía
`JOIN … c.id = p.aseguradora_id`, pero en el modelo multi-tenant la `poliza` ya **no tiene**
`aseguradora_id` (el discriminador se dropeó; `compania` es una sola fila por schema).
Consecuencia: tras un reseed multi-tenant limpio (reset→init→seed, **sin** `datos-aseguradoras.sql`),
`/policies` y `/cases` tiraban **500** (`relation "aseguradora.poliza" does not exist`).
- **Fix**: `InsurerDbSchema` (nuevo, en **common-lib**) deriva `arbiter_bbva` → `aseguradora_bbva` y
  valida el identificador antes de que se concatene al SQL. Va a common-lib a propósito: el bug fue
  que dos módulos derivaran el nombre por su cuenta, y esta es la parte que tienen que compartir.
  - **classification-service**: queries calificadas con el schema del `TenantContext`. Acotado al
    tenant **incluido el historial**: la BD de una compañía no tiene los siniestros de otra, y
    mostrárselos sería una fuga, no un historial más rico. El tenant correcto ya llega — cuando el
    alta se mueve de aseguradora, `ClassificationServiceClient` firma un token de servicio con el
    tenant real.
  - **cases-service**: nuevo `CallerInsurerDatabases` — recorre **todas** las aseguradoras del que
    llama (`insurerIds`, claim firmado), mismo patrón que `InsuredCaseAggregator`. Así la vista
    centralizada de Martina (BBVA + Provincia con el mismo DNI, §6.2) sobrevive al esquema por
    compañía. Se sacó el `JOIN compania`: la aseguradora sale del registro de la plataforma
    (`insurer`), porque con un esquema por compañía `compania.id` es siempre 1 y no identifica nada.
- **Tests**: `InsurerDbSchemaTest` (common-lib), `CallerInsurerDatabasesTest` (cases).
- **Ojo**: `db/datos-aseguradoras.sql` queda **muerto** — era el seeder del schema `aseguradora`
  pelado y ya no lo lee nadie. El workaround de correrlo después del reset trio no hace falta más.
  Borrarlo (junto con `db/init.sql`) es limpieza pendiente, no la hice para no tocar scripts que
  alguien pueda tener a mano.

**D31 · `GET /policies` devolvía las pólizas de cualquier asegurado** — 🟠 **RESUELTO (10/08, backend)**, sin validar en vivo
*Detectado al arreglar D19.* `GET /api/v1/policies?insuredId=…` tomaba el DNI del **query param** y
lo pasaba derecho al adapter, sin compararlo contra el del token: cambiando un número se obtenían
nombre, mail, teléfono, pólizas y sumas aseguradas de cualquier persona de la plataforma. Igual
`GET /policies/{numero}`, con cualquier número de póliza. Es el mismo agujero que **D2**, en el
endpoint de al lado — el frontend mandaba el DNI de la sesión, así que desde la UI no se llegaba,
pero con Postman sí.
- **Fix**: el recorte por identidad vive en `PolicyService` (no en el adapter, para que valga igual
  contra el mock que contra la BD). Un ASEGURADO solo puede pedir su propio DNI →
  `InsuredIdentityMismatchException`, **403**; una póliza de otro lee como **404**, no 403, para que
  la respuesta no confirme que ese número existe. El referente no tiene DNI en el token y consulta
  las de su compañía: ahí el recorte lo hace el conjunto de esquemas que puede leer (D19).
- **Tests**: `PolicyServiceTest` (6 casos, incluido que un rechazo no llegue a consultar el adapter).

### 🟡 Medios

**D9 · Cuatro columnas de `coverage` que no lee nadie** — 🟡 **carencia RESUELTA (10/08)**, el resto abierto
`waiting_period_days` (carencia), `claim_exhausts_coverage`, `covers_family_group`, `is_individual`:
cero consumidores, ni siquiera para mostrarlas. Las cuatro están mapeadas en la entidad
`Coverage` (common-lib) — lo que falta es quién las lea. Revisadas una por una (10/08):

**`waiting_period_days` (carencia)** — ✅ **evaluada**. Días desde el alta de la póliza en que la
cobertura todavía no aplica aunque haya contrato; existe para que no se contrate un seguro por un
hecho ya ocurrido o inminente. El seed tiene **30 días** en las dos coberturas.
- **Fix**: `TemporalRuleEvaluator` la evalúa junto a las otras temporales — `fecha del hecho <
  effectiveFrom + waiting_period_days` bloquea Fast Track y suma un motivo para el analista.
  rules-service la sirve por `/internal/coverage-limits` (misma vía que D10/D11).
- **No confundirla con `minPolicyAgeMonths`** (el criterio de Fast Track de D14, agregado el mismo
  día): la carencia decide el **derecho** —el siniestro no está cubierto— y el mínimo de antigüedad
  decide el **camino** —una póliza muy nueva no va por vía expedita, pero puede pagarse igual.
- **Tests**: `TemporalRuleEvaluatorTest` (+4).

**`claim_exhausts_coverage`** — ✅ **evaluada (10/08)**. Si un siniestro agota la cobertura del período
(seed: Robo FALSE, Hurto TRUE). `CoverageScopeEvaluator` bloquea el Fast Track cuando ya hay un
siniestro **LIQUIDADO sobre la misma póliza** — se agota por póliza, así que un siniestro de otra
póliza del mismo asegurado no cuenta. Para poder distinguirlo, `InsuredHistory.ClaimRecord` ganó
`policyNumber` (el adapter ya joineaba `poliza`, era sumar `p.numero`). Un siniestro RECHAZADO no
consumió nada.

**`covers_family_group`** — ✅ **evaluada (10/08)**, y era la más cara: el hecho que la dispara ("le
robaron el celular a mi hijo") vive en **texto libre**.
- **Se resolvió partiendo el LLM en dos**, igual que D4a y D4b: la extracción devuelve un hecho
  tipado (`DocumentExtraction.AffectedParty`: TITULAR / FAMILIAR / TERCERO / DESCONOCIDO) y
  `CoverageScopeEvaluator` evalúa la regla en código. El modelo nunca decide si hay cobertura.
- **Fuente: la cobertura** (decisión de Fede, 10/08). `coverage.covers_family_group` (config del
  referente, FALSE en las dos del seed) y `poliza.cubre_grupo_familiar` (BD Aseguradora, TRUE en 4 de
  5 pólizas de BBVA) existen las dos y ya se contradecían. Manda la del referente.
- **Solo dispara con un `FAMILIAR` explícito.** `DESCONOCIDO`, o ningún documento leído, deja la
  regla sin evaluar — que el papel no aclare de quién era el equipo no puede costarle la cobertura a
  nadie. El prompt de extracción lo dice con todas las letras: ante la duda, `DESCONOCIDO`, y prohíbe
  deducirlo del apellido o la dirección.
- **Bloquea el Fast Track y aporta motivos; no cierra el expediente** (CLAUDE.md #5): el analista
  firma el rechazo, llega con el fundamento escrito.
- ⚠️ **Solo lee los DOCUMENTOS, no la descripción del siniestro.** El pedido original era evaluarlo
  "sobre el texto de la denuncia **o la descripción del hecho**", y la mitad que falta es la segunda:
  `AffectedParty` sale de la extracción de adjuntos, que es la única pasada tipada que existe. Si el
  asegurado escribe "le robaron el celular a mi hijo" en el campo de descripción del wizard y ningún
  documento lo menciona, **la regla no se entera**. Cerrarlo requiere una pasada de extracción sobre
  el relato — un llamado más al modelo, con la misma forma que la de documentos. Es el enhancement
  natural de esto.
- **Tests**: `CoverageScopeEvaluatorTest` (11).

**`is_individual`** — **pateada (decisión de Fede, 10/08)**. El DER la dibuja como un booleano pelado
y no hay comentario en el script. La lectura de Fede es que es **la negación de `covers_family_group`**,
y el seed es consistente con eso: `covers_family_group = FALSE` / `is_individual = TRUE` en las dos
coberturas. Si son inversas, implementarla sería guardar el mismo hecho dos veces — que es exactamente
cómo se desincronizan. **Cuando se implemente `covers_family_group` hay que decidir si `is_individual`
se dropea del DER o se deriva**, no dejar las dos vivas.

**Las cuatro deberían verse en la solapa Coberturas**, que ya existe y edita nombre, cláusula,
franquicia, plazo de denuncia, máx. eventos por año y exclusiones. No hay que construir pantalla:
hay que sumar campos a una que ya está.

> **Nota de diseño — reglas que dependen del relato (`covers_family_group`, y también D4b).**
> Fede lo planteó así (10/08): estas validaciones se corren sobre el expediente, y si de la
> descripción o del OCR surge que el damnificado es un familiar, la regla se cae y el caso se
> resuelve por esa razón, sin pasar por la interpretación del modelo.
>
> El problema de orden: *"¿el damnificado es un familiar?"* solo se sabe **leyendo el texto**, y
> leer texto es lo único que el motor determinístico no puede hacer. Una regla no puede correr
> "antes del LLM" si su insumo sale del LLM.
>
> La salida ya está en la casa, y es la misma que se usó para D5: **partir el LLM en dos**. Una
> pasada de **extracción** que devuelve un hecho tipado (`damnificado = titular | familiar |
> tercero`) y una **regla en código** que evalúa `covers_family_group == false && damnificado ==
> familiar → no cubierto`. El modelo nunca decide la regla: solo provee el dato que la regla
> necesita, y esa pasada corre **antes** de la clasificación. Es exactamente el patrón de D4a
> (extraer con el modelo, decidir con código) y lo que también destraba **D4b**, que necesita el
> IMEI como campo y no como prosa.
>
> **Lo que sí hay que corregir del planteo**: la regla **no puede rechazar la liquidación sola**.
> La decisión #5 del `CLAUDE.md` es inmutable — human-in-the-loop obligatorio, sin resolución
> automática, *ni siquiera para Fast Track*. Lo que corresponde es lo que ya hace la exclusión de
> cobertura de D3: **bloquear el Fast Track, derivar a revisión manual sin llamar al LLM, y dejar
> el motivo a la vista con su fila en `rule_result`**. El analista sigue firmando el rechazo. El
> mecanismo existe (`CoverageRuleEvaluator`), así que es reusarlo, no construirlo.

**D10 · La regla estrella del ramo no se aplica** — 🟡 **tope de eventos RESUELTO (10/08, backend)**, sin validar en vivo
`max_eventos_anuales` y `segundo_evento_pct` ("2 eventos por año y póliza, el segundo al 50%") se
guardaban y no los evaluaba nada.
- **Fix (tope de eventos)**: `TemporalRuleEvaluator` cuenta los siniestros del asegurado en el ramo
  dentro de los últimos 12 meses (de `InsuredHistory.claims`) y bloquea el Fast Track + suma un factor
  para el analista si el actual supera `coverage.max_events_per_year`. rules-service sirve el límite
  por `/internal/coverage-limits`. **Queda afuera** el `segundo_evento_pct` (cobertura al 50% del
  segundo evento) — es cálculo de indemnización, no un gate de clasificación.

**D11 · El plazo de denuncia no se evalúa** — 🟡 **RESUELTO (10/08, backend)**, sin validar en vivo
`report_deadline_hours` era editable y solo se leía para el CRUD.
- **Fix**: `TemporalRuleEvaluator` compara `reportedAt - occurred_at` (la denuncia a la aseguradora es
  el alta del caso, `Case.reportedAt`, que ahora viaja en `ClaimReport`) contra
  `coverage.report_deadline_hours` (servido por `/internal/coverage-limits`). Fuera de plazo (o
  denuncia anterior al hecho) bloquea Fast Track + factor para el analista.

**D12 · `police_report_at` no se captura ni se evalúa** — 🟡 **captura RESUELTA (Aylén, 09/08)**, evaluación pendiente
Existía en `CaseRequest` y en la entidad, pero **el wizard nunca lo mandaba** y ningún servicio lo
lee. La regla "denuncia policial dentro de las 48 hs" era literalmente inverificable.
- **Fix de la captura**: el wizard pide fecha + hora de la denuncia policial, y solo cuando la
  agenda documental del ramo incluye `police_report` — se deriva de `requiredDocTypes`, la misma
  fuente que arma los slots de adjuntos, así que si el referente saca ese documento de un ramo el
  campo desaparece solo. Vacío ⇒ se manda `undefined`: "no hubo denuncia policial" es un caso
  legítimo y distinto de "hubo pero no sé cuándo"; mandar una fecha inventada sería peor, porque la
  regla del plazo la evaluaría como real.
- **Sin validación de coherencia en el cliente, a propósito**: que la denuncia policial no pueda ser
  anterior al hecho, y el plazo de las 48/72 hs, son reglas de negocio → van al motor, no al
  frontend (es la lección de D4a). El wizard solo topea la fecha a hoy, igual que `eventDate`.
- **La evaluación queda como enhancement** (decisión de Fede, 10/08). El razonamiento: la fecha con
  la que hay que comparar es la que **dice la constancia**, y esa la extrae el OCR. Recién ahí se
  compara, y lo que salga alimenta el gate de Fast Track — una denuncia policial fuera de plazo no
  debería liquidarse por vía expedita.
- ✅ **RESUELTO (10/08)**, sin validar en vivo. Se destrabó junto con D4b (la constancia ya devuelve
  `documentDate` tipado) y se cerró con tres piezas:
  1. **`ClaimReport.policeReportAt` viaja al motor.** Se capturaba desde el 09/08 y se quedaba en
     cases-service.
  2. **El plazo se evalúa** en `TemporalRuleEvaluator`: `policeReportAt - eventDate` contra **72 hs**.
     Bloquea Fast Track. Una denuncia policial anterior al hecho se marca como dato inconsistente.
     Sin `policeReportAt` la regla no participa — "no hubo denuncia policial" es un caso legítimo.
  3. **El cruce declarado-vs-papel**, que es la señal que justificaba guardar las dos fechas por
     separado: `DocumentInconsistencyEvaluator` compara `policeReportAt` contra el `documentDate` de
     la constancia. Se compara **por día**, no por hora: el asegurado declara hora exacta y la
     constancia habitualmente no, y exigir coincidencia al minuto haría sospechoso a cualquiera que
     redondeó.
- ⚠️ **El umbral de 72 hs es PROVISORIO y está fuera de la BD.** Vive en la propiedad
  `arbiter.rules.police-report-deadline-hours` (default 72), no como constante enterrada, para que al
  menos se pueda cambiar por entorno sin recompilar. **Contradice la decisión #12** (reglas en BD) y
  es deuda asumida a sabiendas (decisión de Fede, 10/08). Ver abajo por qué no tiene dónde vivir.
- **Tests**: `TemporalRuleEvaluatorTest` (+4), `DocumentInconsistencyEvaluatorTest` (+2).
- **Ojo con el umbral cuando se implemente**: `coverage` tiene **una sola** columna de plazo
  (`report_deadline_hours`) y **ya la usa D11** para el plazo de denuncia *a la aseguradora*. Son dos
  plazos distintos (avisarle a la compañía vs. ir a la policía): reusar la columna haría que un
  número configurado gobierne dos reglas. Las salidas son columna nueva en `coverage` (**cambio de
  DER**) o modelarlo como regla evaluable en `insurer_rule`, igual que la exclusión de cobertura de
  D3 — esta última no toca esquema y trae auditoría en `rule_result` gratis, que es lo que D10/D11/D13
  todavía no tienen.
- Falta también `ClaimReport.policeReportAt`: hoy el dato se captura y se guarda en `Case`, pero
  **no viaja** al motor. Es la parte fácil.
- **Decisión de diseño registrada**: `police_report_at` es la **declaración del asegurado**. Cuando
  exista extracción estructurada del documento (H0007), la fecha que diga la constancia va en un
  dato **aparte** — si sobreescribiera a esta se pierde el cruce, y la discrepancia entre lo
  declarado y lo que dice el papel es justamente la señal que le daría contenido al
  `DocumentInconsistencyEvaluator` (**D4b**).

**D13 · Vigencia de la póliza vs fecha del hecho: no se valida** — 🟡 **RESUELTO (10/08, backend)**, sin validar en vivo
`effectiveFrom`/`effectiveTo` se leían de la BD Aseguradora y solo se imprimían en el prompt.
- **Fix**: `TemporalRuleEvaluator` valida que `occurred_at` caiga dentro de
  `[effectiveFrom, effectiveTo]` de la `InsuredPolicy`. Fuera de vigencia bloquea Fast Track + factor
  para el analista.

> **Nota común a D10/D11/D13 (auditoría):** estos tres se evalúan por código y **bloquean el Fast
> Track**, pero **no se auditan en `rule_result`** todavía: sus umbrales son columnas de `coverage`,
> no filas de `insurer_rule`, y `rule_result.rule_id` es FK NOT NULL a `insurer_rule`. Auditarlos
> requiere modelarlos como reglas de aseguradora, igual que las exclusiones (ver
> plan-reglas-evaluables.md §1.1). Además siguen pudiendo aparecer como texto en las "reglas de
> negocio" del prompt: la limpieza de ese texto (tipo paso 6) es aparte.

**D14 · Criterios de Fast Track que la UI promete y el motor no tiene** — 🟡 **RESUELTO (10/08)**, sin validar en vivo
El referente configuraba **siete** cosas en la solapa Fast Track y al backend llegaban **cuatro**:
`saveFastTrack()` armaba el DTO a mano y `minPolicyAgeMonths`, `priorClaimsWindowMonths` y la lista
`criteria` quedaban en el draft del componente. Tampoco existían en `FastTrackConfigDto` ni en
`BusinessRules.FastTrackThresholds`.
- **Había un bug silencioso detrás de la ventana**, que no estaba en el registro: sin
  `priorClaimsWindowMonths`, `FastTrackValidator` comparaba contra `history.previousClaimsCount()`,
  el conteo **de toda la vida**. O sea que "máximo 1 siniestro previo" significaba *nunca en la
  historia*, no *en los últimos 24 meses* — un cliente de quince años con dos siniestros viejos
  quedaba fuera del Fast Track para siempre, y el referente creía estar configurando una ventana.
- **Fix de los umbrales**: los dos campos viajan por el camino que ya existía
  (`FastTrackConfigDto` → JSONB de `insurer_rule.configuration` → `/internal/fast-track` →
  `FastTrackThresholds` → `FastTrackValidator`). Sin cambio de esquema. La ventana se cuenta hacia
  atrás **desde el hecho**, no desde hoy, igual que el tope de eventos anuales de D10: el criterio es
  la situación del asegurado cuando ocurrió el siniestro. La antigüedad sale de
  `policy.effectiveFrom` contra la fecha del hecho; sin fecha de alta **no se asume antigüedad** y no
  hay Fast Track (solo procede sobre lo verificable).
- **`criteria` era otro problema, y peor de lo registrado.** No estaba sin usar: el
  `MockRulesAdapter` tiene una lista **hardcodeada** ("Monto reclamado inferior al 50% de la suma
  asegurada"…) que `PromptBuilder` inyecta al prompt bajo "CRITERIOS FAST TRACK". El referente
  editaba su lista, se descartaba, y al modelo le seguía llegando un texto que nadie del negocio
  escribió — y que podía **contradecir** al umbral real persistido (50% en el texto, 30% en el
  número). Ahora se persiste y **reemplaza** al del mock: si el referente guardó una config, manda
  su lista aunque esté vacía (el prompt va sin la sección) en vez de resucitar la del mock.
  Los criterios **no deciden nada** — el gate son los umbrales; son la descripción de la política
  para el modelo, y el hint de la UI ahora lo dice.
- **Tests**: `FastTrackValidatorTest` (+6).

**D15 · El panel de ramos salía de un mock** — ✅ **RESUELTO (10/08)**
La lista de ramos sale del catálogo real (`GET /api/v1/rules/branches`, tabla `branch`) vía
`BranchesService` — se eliminó el `SEED_RAMOS`/`RulesConfigService` del front. Se sumó el **CRUD
completo en el backend**: `BranchController` (rol REFERENTE) con `POST` (alta), `PUT /{id}` (renombre)
y `DELETE /{id}` (baja), servidos por `BranchCatalogService` (nombre único → 409; baja con guarda de
referencias → 409 si el ramo tiene hechos generadores/coberturas/reglas). En la **UI** se expone solo
el **renombre**: el alta y la baja de ramos quedaron fuera de la pantalla a pedido (el catálogo es
fijo, lo administra el seed) — los endpoints siguen ahí por si se reactivan.
- **Ojo (diseño)**: `branch` es un catálogo **global** (`arbiter_common`, compartido por todas las
  aseguradoras), no una config por aseguradora — crear/borrar un ramo toca el catálogo maestro. Si en
  el futuro se quiere que cada aseguradora tenga su propio catálogo, hay que mover/rediseñar `branch`.
- **Test**: `BranchCatalogServiceTest`.

### 🔵 Bajos

**D16 · PEP se pide, se guarda y no lo usa nadie** — 🔵 **RESUELTO (10/08)**, sin validar en vivo
Ni scoring, ni prompt, ni pantalla del analista. Cero consumidores — con el agravante de que la
grilla del expediente ya tenía la fila `PEP (declarativo)` hardcodeada en `null` (era también una de
las filas muertas de D28).
- **Fix**: `pep` viaja en `CaseResponse`/`ExpedienteResponse` desde `Insured.pep`, y la fila de la
  grilla se llena — ubicada **junto a Asegurado y DNI**, que es donde el analista la busca, y no al
  final de la grilla como estaba. "No" se muestra como valor, no como "Sin datos": la ausencia de
  declaración y una declaración negativa no son lo mismo.
- **Decisión: NO entra al scoring ni al prompt, a propósito.** PEP es una declaración de debida
  diligencia UIF/PLA — la norma pide que un humano la mire, no que suba un puntaje. Usarla como
  señal de fraude es otra cosa, y no es lo que el campo dice. Si algún día tiene que pesar, va como
  regla configurable por el referente (decisión #12), no como un `if` en el motor.
- **Sigue pendiente** (no es este defecto): cuando PEP=Sí faltan los campos de detalle (cargo,
  relación con la persona expuesta).

**D17 · La advertencia "los pesos deberían sumar 100%" contradice al motor** — 🔵 **RESUELTO (10/08)**, sin validar en vivo
`RiskScoringService` normaliza (`score = weightedSum / totalWeight`): los pesos son **relativos** y su
total no importa — el score sale en [0,1] sumen 100%, 190% o 7. La UI igual advertía que "deberían
sumar 100%", y el seed suma **190%** (0,45 + 0,35 + 0,20 + 0,50 + 0,40), así que una instalación
recién seedeada mostraba un error de fábrica que no era un error.
- **Se sacó la regla, no se ajustó el seed.** Rebalancear no arregla nada: apenas el referente activa
  o desactiva un factor vuelve a "romperse", y estaría haciendo aritmética que el motor ya hace solo.
  La prueba de que la restricción no significaba nada: dividir todos los pesos por dos no cambia el
  score ni un decimal, porque numerador y denominador se achican igual.
- **En su lugar se muestra el peso efectivo** (`peso / total de los activos`), que es lo que el motor
  aplica de verdad. Con el seed actual `amount_ratio` no pesa 45% sino **23,7%** — antes el número
  que el referente leía no era el que se usaba.

**D18 · Dos tests rotos desde antes** — ✅ **RESUELTO (Aylén, 09/08)** · eran **tres**
- `ClassificationOrchestratorIntegrationTest.lowAmountFirstClaimUpToDate_shouldFastTrack…` — armaba
  un `ClaimReport` sin `coverageId`, y `MockRulesAdapter.RULES_BY_COVERAGE` keyea **por id de
  cobertura**, así que caía a las reglas genéricas (sin thresholds) → el caso terminaba en el LLM y
  el mock sin stub devolvía null → **NPE**, no un assert fallido. **Fix**: `.coverageId(1L)`
  (cobertura "Robo de celular", la del hecho generador del claim). Es el único test de la clase que
  prueba el gate determinístico de verdad (`verifyNoInteractions`); los otros dos que esperan
  `FAST_TRACK` stubean el classifier, por eso pasaban.
- `RulesServiceApplicationTests.contextLoads` — la causa no era "no encuentra datasource" sino
  `PSQLException: FATAL: la autentificación password falló para el usuario "arbiter"`: el módulo
  **no tenía ninguna infraestructura de test** y el `@SpringBootTest` levantaba contra el Postgres
  del `application.yml`. **Fix**: Testcontainers + `support/AbstractPersistenceIT`, espejando el de
  cases-service (mismo patrón singleton container, mismo `ddl-auto=update` acotado a tests).
- **`ReportsServiceApplicationTests.contextLoads`** — *no estaba en el registro*: idéntica causa que
  el anterior. Mismo fix. Sin esto el reactor seguía en rojo aunque los otros dos estuvieran
  arreglados, que era justamente el punto de cerrar D18.

**Estado verificado:** `mvn test` sobre el reactor completo → **7/7 módulos, 334 tests, 0 fallas**
(common-lib 4 · auth 80 · classification 100 · rules 3 · cases 146 · reports 1).

---

## 4 · Estado de los defectos

| ID | Severidad | Estado | Dueño |
|----|-----------|--------|-------|
| D1, D2, D20 | Crítico | ✅ Resuelto (09/08) — 146 tests verdes en cases-service, **sin validar en vivo** | Aylén |
| D3 | Alto | ✅ Resuelto (10/08, backend) — exclusiones evaluables + `rule_result`, **sin validar en vivo** | Aylén |
| D4c | Alto | ✅ Resuelto (10/08) — `rule_result` con escritores | Aylén |
| D4a | Alto | ✅ Resuelto (10/08) — reglas duras evaluables (D3+D10/D11/D13) + paso 6 (prompt `classification-v3` con el veredicto del motor); quedan solo las interpretativas en el prompt. Sin validar en vivo | Aylén |
| D4b | Alto | ✅ Resuelto (10/08) — campos tipados en la extracción (`extraccion-documento-v3`) + `poliza.imei` como operando + evaluador real (IMEI, fecha, importe). **Sin validar en vivo** | Aylén |
| D5 | Alto | ✅ Cerrado (10/08) — fecha/monto/lugar (v2) + señales visuales desde la extracción (`classification-v4`); la imagen al clasificador se descarta **por diseño** (§5). Sin validar en vivo | Aylén |
| D19 | Alto | ✅ Resuelto (10/08, backend) — los **dos** adapters leen `aseguradora_<tenant>`; **sin validar en vivo** | Aylén |
| D31 | Alto | ✅ Resuelto (10/08, backend) — `/policies` acotado al DNI del token; **sin validar en vivo** | Aylén |
| D10, D11, D13 | Medio | ✅ Resuelto (10/08, backend) — reglas duras temporales (`TemporalRuleEvaluator`), bloquean Fast Track; **sin auditar en `rule_result`** y **sin validar en vivo** | Aylén |
| D9 | Medio | ✅ Resuelto (10/08) — carencia + alcance de cobertura (grupo familiar, cobertura consumida) evaluados; `is_individual` pateada por redundante. **Sin validar en vivo** | Aylén |
| D14 | Medio | ✅ Resuelto (10/08) — antigüedad mínima + ventana de siniestros evaluadas, `criteria` persistidos y reemplazando al mock; **sin validar en vivo** | Aylén |
| D12 | Medio | ✅ Resuelto (10/08) — `policeReportAt` viaja al motor, plazo evaluado (72 hs **provisorias por propiedad**) y cruce declarado-vs-constancia. Umbral configurable por aseguradora = enhancement. **Sin validar en vivo** | Aylén |
| D15 | Medio | ✅ Resuelto (10/08) — lista real + CRUD de ramos (`BranchController`) | Aylén |
| D16 | Bajo | ✅ Resuelto (10/08) — PEP visible en los datos del asegurado; fuera del scoring por decisión | Aylén |
| D17 | Bajo | ✅ Resuelto (10/08) — se sacó la regla del 100% y se muestra el peso efectivo; **sin validar en vivo** | Aylén |
| D18 | Bajo | ✅ Resuelto (09/08) — reactor completo verde, 334 tests | Aylén |
| **D21–D30** | Alto→Bajo | ✅ Todos resueltos (10/08) — relevo de desconexiones back↔front, detalle y matices en §9. Sin validar en vivo | Aylén |

---

## 5 · Fuera de alcance: declarado pero no implementado

**No son defectos** — son funcionalidad que todavía no se construyó. Importa tenerlo escrito para que
el plan de pruebas no liste como componente algo que no existe:

- **Notificaciones al asegurado.** La decisión #9 dice SendGrid en cada cambio de estado.
  `Notification` tiene entidad y repository con **cero escritores**; SendGrid solo se usa en
  `auth-service` para invitar usuarios.
- **Plazo legal de respuesta.** `response_deadline` se calcula al crear el expediente (30 días,
  Ley 17.418 art. 56) y **nunca se vuelve a leer**. Nada avisa cuando vence — y el silencio de la
  aseguradora equivale a aceptación.
- **Reportes y Dashboard del referente.** `reports-service` tiene solo la entidad `Metric` y su
  repository: **sin controllers, sin services**. Las dos pantallas dicen "TODO MOCK" en su propio
  encabezado. → **Marcar explícitamente fuera de alcance de esta iteración de testing.**
- **Detección de fraude documental.** Hoy la cascada cubre reuso interno (CLIP + pgvector) y match web
  (Google Vision). Un documento *fabricado* no dispara ninguno de los dos. Desde el 10/08 hay una
  señal parcial: la pasada de extracción reporta indicios visuales de manipulación (D5), que llegan
  al analista como factores. **No es detección**: es una observación no determinística, sin
  `rule_result` y sin cruce contra los datos del bien (eso es D4b + H0007).
- **"Marcar como fraude" y "derivar" no existen como flujo.** Las columnas `cases.fraud_determined`
  y `cases.destination` están en el DER y en la entidad, pero el analista hoy solo aprueba o
  rechaza, y `destination` no tiene valores definidos en ninguna fuente (el DER la dibuja como un
  `VARCHAR(40)` suelto). **Pregunta abierta al equipo**: ¿son salidas reales del proceso —pago /
  rechazo / derivación a investigación— o quedaron del modelo y se dropean? Hasta que alguien
  conteste, no se les inventa semántica (ver D29).
- **La imagen no llega al modelo de clasificación, y no va a llegar.** Decisión, no pendiente: la
  ventana de 32k (decisión #2) y el pipeline de fraude por CLIP/pgvector (decisión #11). Lo visual
  se resuelve donde ya se mira la imagen — la extracción. Ver D5.
- **Mensajería asegurado ↔ analista.** La solapa "Conversación" del expediente
  (`expediente-detail.component.html`) es un placeholder vacío — no existe backend de chat. Distinto
  de las notificaciones SendGrid (que son one-way, cambio de estado): esto sería bidireccional.

---

## 6 · Qué se puede testear ya (batería sugerida)

Ordenado por lo que más rinde:

1. **Seguridad y permisos de acceso** — D1, D2 y D20 siguen siendo casos de prueba redondos con
   Postman, pero ahora como **regresión**: el resultado esperado pasó de "entra igual" a 404 (subir
   a expediente ajeno), 403 (denunciar a nombre de otro, o con token de referente) y 422 (DNI de uno
   con póliza de otro). Sumar: lectura de expediente ajeno (esto **sí** ya estaba bien cerrado: 404,
   no 403, para no filtrar existencia), descarga de documento ajeno (también cerrado), y la matriz de
   roles sobre los endpoints de `CaseController` (`@PreAuthorize` por endpoint). Sumar también
   **D31**: `GET /policies?insuredId=<el DNI de otro>` → 403, y `GET /policies/{póliza ajena}` → 404.
2. **Portal del asegurado** — es real, no mock. Ver solo los propios (incluidos los de las dos
   aseguradoras: Martina es cliente de BBVA y Provincia con el mismo DNI), subir documentación
   faltante y verificar que el expediente vuelva a `PENDING_CLASSIFICATION` y se reclasifique,
   seguimiento de estados con su timeline.
3. **Validaciones de alta** — batería de negativos barata y real: `@PastOrPresent` en la fecha del
   hecho, `@NotBlank` en 7 campos, `@NotNull` en PEP y consentimiento de imagen, archivo > 10 MB,
   archivo que no es imagen ni PDF, y los 422 de referencias no resolubles.
4. **Los 6 casos del ramo** (§2.4), con los rojos documentados como defecto.
5. **Fast Track configurable** — el mismo caso 1 sirve para probar el RNF de mantenibilidad: ramo
   nuevo operativo **solo con configuración del referente**, sin deploy.
6. **Regresión multi-tenant** — aislamiento entre esquemas con la identidad compartida de Martina. Ya
   se verificó en vivo en agosto; como caso escrito vale para cada release.

---

## 7 · Historias candidatas (a decidir si se crean)

1. **Validación de reglas duras de cobertura** — cubre D3 + D4a + D4c: que el motor evalúe cobertura
   vs hecho generador y las exclusiones, y **escriba en `rule_result`**. Cierra además el requisito de
   auditoría de la Disposición 2/2023. Es la más valiosa de la lista.
2. **Cerrar los dos agujeros de autorización** — D1 y D2. Chico en código, Crítico en severidad.
3. **Reglas temporales y de frecuencia** — D10, D11, D12, D13: plazo de denuncia, vigencia al momento
   del hecho, tope de eventos anuales. Todas necesitan lo mismo: que alguien evalúe fechas.
4. **Alerta de vencimiento del plazo legal** — §5, la de consecuencia legal más concreta.
5. **CRUD de ramos** — D15, para que el panel deje de mentir.

---

## 8 · Notas operativas

- **Catálogo re-seedeado**: Tecnología pasó de branch 3 a **2**. Confirmar ids contra Railway antes de
  fijarlos en los casos de prueba.
- **La sesión vive en memoria**: cualquier recarga completa (HMR al editar, o navegar por URL en vez
  de por los links del nav) desloguea.
- **JDK 21 explícito** para compilar (`JAVA_HOME` del sistema resuelve a 26 y rompe Lombok).
- **Se trabaja contra Railway** con `scripts/run-local.ps1 <módulo>`, nunca contra el Postgres local
  del `docker-compose.yml`. Si Railway se satura de conexiones, el único remedio es reiniciar el
  servicio desde el panel (corta a todo el que esté conectado — avisar antes).
- **Template de casos de prueba** (columnas del `.xlsm` de la cátedra): Componente Funcional · Caso de
  Uso · # Caso de Prueba · Caso de Prueba · Regresión · Descripción · Precondiciones · Datos de
  Entrada · Pasos · Resultado Esperado · Diseñador · Fecha ‖ Resultado · Severidad · Prioridad ·
  Observaciones del Resultado · Tester.
- **Tests con Testcontainers**: sufijo `*Tests.java`, nunca `*IT.java` (Surefire los excluye en
  silencio).

---

## 9 · Relevo de desconexiones back↔front (10/08)

Barrido del código —fuera de lo que ya cubren D1–D20 y §5— para "dejar la app realmente conectada".
Foco: cosas mockeadas, y config/datos que un lado establece y el otro no usa. Ordenado por impacto.

### 🟠 Altos

**D21 · El scoring de fraude que configura el referente NUNCA llega al motor** — ✅ **RESUELTO (10/08, backend)**, sin validar en vivo
`ScoringConfigurationController` sirve `GET|PUT /api/v1/rules/scoring` y persiste el scoring del
referente, pero no llegaba al motor: no había `/internal/scoring` y `RulesRestAdapter` usaba el
scoring del `MockRulesAdapter`.
- **Fix**: nuevo `GET /api/v1/rules/internal/scoring` (system-to-system) + `overlayScoring` en
  `RulesRestAdapter`, mismo patrón que Fast Track / coverage-limits. Ahora `RiskScoringService` usa los
  factores/bandas que carga el referente; si no hay config (o no se puede leer) cae al baseline de
  referencia. El panel de scoring **ya afecta la clasificación**.
- **Queda**: `cases.scoring_configuration_id` sigue sin escribirse (D29) — falta persistir qué config
  de scoring se usó, para la auditoría.

**D22 · El `riskScore` numérico y el `riskBreakdown` se calculan y viajan, pero el front no los muestra** — ✅ **RESUELTO (10/08, front)**, sin validar en vivo
El back ya entregaba `riskScore` + `riskBreakdown` en `CaseResponse` (el `riskBreakdown` lo joina
`CaseAnalysisRepository`), pero el front solo usaba `riskBand`.
- **Fix**: `ExpedienteResponse` gana `riskScore` + `riskBreakdown` (`RiskBreakdownItem`). A primera
  vista, la vista del analista muestra solo el **gauge** (nivel de riesgo); el **desglose** (score de
  fraude en % + tabla por factor: nombre + aporte `rawScore × peso` + `rationale`, ordenada por el que
  más pesó) vive en una **sub-pestaña "Desglose de riesgo"** junto a Resumen/Documentación/etc. El
  score deja de ser una caja negra. Junto con D21, el scoring queda conectado de punta a punta.

### 🟡 Medios

**D23 · Alta de usuario: `sector` y `fechaIngreso` se descartaban en silencio** — ✅ **RESUELTO (10/08, front)**
El front mandaba `sector` (obligatorio) + `fechaIngreso` que el back ignoraba, y la grilla mostraba
columnas Sector/Fecha de ingreso siempre "—". El modelo multi-tenant de usuarios **no tiene esas
columnas** (el DER las dropeó).
- **Fix (alineación al back)**: se sacaron `sector`/`fechaIngreso` del alta de usuario (form + DTOs
  `CreateUserRequest`/`UserResponse`) y las dos columnas de la grilla. La UI deja de capturar datos
  que se descartan.
- **Si se quieren de verdad**: es una historia de schema (columna en el perfil por rol —
  `insurer_referent` / `claims_analyst`), no un campo de UI suelto.

**D24 · "Aceptar / Modificar" la clasificación sugerida (vista analista) no persistía** — ✅ **RESUELTO (10/08, front)**
`acceptClassif()`/`modifyClassif()` solo seteaban una señal local (aparentaban una acción que no
ocurría). **Fix**: se quitaron los botones y el estado local; la tarjeta de recomendación ahora aclara
que es no vinculante y que la decisión real se registra en Aprobar/Rechazar (`/decision`), que es lo
único que persiste.

**D25 · "Suma asegurada" por cobertura: input editable que no se persistía ni cargaba** — ✅ **RESUELTO (10/08, front)**
El input "Suma asegurada ($)" de la solapa Coberturas se descartaba al guardar (no está en el DER: la
suma vive en la póliza). **Fix**: se sacó el input y sus helpers (`coverageAmountStr`/`setCoverageAmount`).

**D26 · Filtro "Tipo de siniestro" de la bandeja: catálogo hardcodeado (con valores inexistentes)** — ✅ **RESUELTO (10/08)**
`bandeja.component.ts` tenía una lista fija de 4 con valores que no existen (`Siniestro general`,
`Rotura accidental` solo en un ramo) → filtraba vacío. **Fix**: nuevo `GET /api/v1/claim-causes/all`
(cases-service, nombres distintos de todos los ramos) que la bandeja carga en el constructor y usa
como opciones del filtro. Deja de estar hardcodeado.

**D27 · `PolicySnapshot`: entidad con cero escritores y cero lectores** — 🟡 **RESUELTO (escritura, 10/08)**, sin validar en vivo
`PolicySnapshot` + `PolicySnapshotRepository` no se inyectaban en ningún lado y `Case.policySnapshot`
(FK) nunca se seteaba (el javadoc lo admitía). La "foto" de la póliza al momento de la denuncia —que
el scoring lee para `policy_standing`/`claim_frequency` y que pide la reproducibilidad de la Disp. SSN
2/2023— **no se guardaba**. Consecuencia concreta: los dos factores salen de la BD Aseguradora **en
vivo** (`ClassificationOrchestrator.fetchContext()`), que es un sistema externo que sigue cambiando —
"se puso al día" o "le liquidaron otro siniestro" mueven el score de una re-corrida y no queda rastro
de qué vio la primera. Hoy `llm_analysis` guarda *qué se decidió* y `risk_analysis` *cuánto puntuó*;
faltaba *sobre qué*.
- **Quién escribe: classification-service**, en `fetchContext()`, que es donde el dato entra a la
  decisión. **cases-service quedó descartado**: `createCase` ni toca la BD Aseguradora (trabaja
  contra la copia local `policy`) y no tiene `upToDate` ni `previousClaims`; fotografiarla desde ahí
  daría un registro que no coincide con lo que la decisión usó, peor que no tener ninguno.
- **Cómo**: `PolicySnapshotRepository` (classification), JDBC plano sobre un set acotado de columnas
  — mismo patrón y misma razón que `CaseOutcomeRepository` (la clasificación es asincrónica: cuando
  cases-service pollea, el request ya no existe). No se mapea la entidad de nuevo: la de
  cases-service queda como el lado **lectura**.
- **Una fila por expediente, se pisa** (decisión (b), tomada con Fede): entre la primera corrida y la
  re-clasificación por documentación faltante pasan horas y la póliza es la misma. Guardar una fila
  por corrida dejaría filas huérfanas — la tabla **no tiene `case_id`**, el vínculo es
  `cases.policy_snapshot_id`. Si algún día hace falta el historial completo, es sumar `case_id` **al
  DER primero** y recién ahí acá.
- **`in_force` se derivó de la vigencia** (el hecho dentro de `effectiveFrom..effectiveTo`) y no de
  `estado_contrato`, que en el seed es `'ACTIVA'` en las 8 pólizas y no distingue nada. La ventana
  vive ahora en `InsuredPolicy.inForceOn()`, compartida con la regla dura D13 en vez de duplicada.
  Sin fechas devuelve `false`: no se afirma una vigencia que no se pudo verificar (mismo criterio que
  el dueño de la póliza en D2), y el dato crudo con sus nulls queda igual en `insurer_db_payload`.
- **Best-effort**, igual que el scoring y la cascada de fraude que lo rodean: una fila de auditoría
  que falla no puede voltear una clasificación que un analista está esperando. Se loguea como ERROR.
  Si el equipo prefiere que sea bloqueante, es cambiar el `catch` — decisión de negocio, no técnica.
- **Tests**: `ClassificationOrchestratorSnapshotTest` (5), `InsuredPolicyTest` (4).
- **Queda abierto**: (1) **nadie la lee todavía** — igual que `rule_result` cuando se cerró D4c; la
  lectura natural es `Case.policySnapshot` en la vista del analista, y quedó como *enhancement*
  aparte. (2) **El SQL del repository no está cubierto por tests**: es la brecha ya conocida de que
  Testcontainers no corre `init-multitenant.sql`, así que `policy_snapshot` y `cases` no existen en
  el contenedor de este módulo. Lo testeado es el cableado y los valores, no el upsert contra
  Postgres.

### 🔵 Bajos

**D28 · Filas muertas en la grilla del expediente (analista)** — 🔵 **RESUELTO (10/08)**
`expediente-detail.component.ts` hardcodea `value: null` permanente. Son **cinco**, no cuatro, y no
todas son el mismo problema:

| Fila | Qué hay detrás | Estado |
|------|----------------|--------|
| `Hecho generador` | **duplica a `Causa`**, que sí trae `claimCause` | ✅ Borrada (10/08) — el mismo concepto dos veces, uno vivo y uno muerto |
| `PEP (declarativo)` | `Insured.pep`, que ya se capturaba | ✅ Llenada (10/08, D16) |
| `Canal de origen` | nada, en ninguna capa: el concepto no existe en el modelo | ✅ Borrada (10/08) |
| `N° de certificado` | `poliza.nro_certificado` está en la BD Aseguradora, no en la `Policy` local | ✅ Borrada (10/08) |
| `Tomador` | ídem: `poliza.titular_id` está afuera, no en `Policy` | ✅ Borrada (10/08) |

**Por qué se borraron las dos últimas en vez de llenarlas.** `Tomador` y `N° de certificado` son el
mismo concepto faltante: la **póliza colectiva**. Ahí el contrato lo tiene un tomador (típicamente el
banco, que es exactamente como BBVA vende "Celular Protegido") y cada cliente entra con un
*certificado de incorporación* — el número que lo identifica adentro de una póliza madre compartida
por miles. Ese es el caso en que las dos filas valen.

**No es nuestro modelo.** En el seed cada póliza es individual, tiene su propio `numero`, y
`nro_certificado` es 1:1 con ella (`621242`…`621246`): no identifica nada que el número de póliza no
identifique ya. Están en el esquema porque copia la forma de la API real de BBVA, no porque los
datos las necesiten. Llenarlas costaría además una columna nueva en `policy` → **cambio de DER**,
por un dato redundante. Si algún día se modelan pólizas colectivas, vuelven las dos juntas.

**D29 · Columnas de `cases` sin uso** — 🔵 **`scoring_configuration_id` RESUELTO (10/08)**; las otras dos no son código faltante
Tres columnas de `Case.java` sin writer, y son **dos problemas distintos**:
- **`scoring_configuration_id`** — ✅ resuelto. El javadoc decía que quedaba en null porque
  "classification-service todavía no llama a rules-service", y **eso quedó falso con D21**: hoy el
  scoring del referente sí llega al motor. Lo que faltaba era que el **id** de la config viajara.
  Ahora `ScoringConfigDto` (rules) lo expone en las lecturas —se ignora al guardar, hay una sola
  config por aseguradora—, `BusinessRules.ScoringConfig` y `RiskScore` lo llevan, y
  `ClassificationResultsService` lo escribe junto al `risk_analysis` vía `CaseOutcomeRepository`.
  Cierra el "queda" que D21 dejaba anotado: sin esto, un score auditado no se puede explicar después
  de que el referente cambie los pesos. Null cuando el score salió del baseline (no hay fila que
  apuntar, y la FK lo rechazaría).
  - **Tests**: `ClassificationResultsServiceTest` (+2).
- **`fraud_determined` y `destination`** — **no se tocan, y no son un defecto de código**: son
  decisiones de producto que no existen. No hay flujo de "marcar como fraude" (el analista solo
  aprueba o rechaza) y `destination` no tiene valores definidos en ningún lado — el DER la dibuja
  como un `VARCHAR(40)` pelado. Inventarles semántica sería inventar reglas de negocio. Movidas
  a §5 con la pregunta abierta.

**D30 · Endpoints sin consumidor (solo Postman/PoC)** — 🔵 **CERRADO (10/08)**
`ClassificationController` (`POST /api/v1/classifications`, `GET /classifications/results`, "isolated
testing") e `ImageEmbeddingController` (`POST /api/v1/image-embeddings/check-duplicate`, "(PoC)") no
los llama nadie (ni front ni back — el flujo real entra por `/api/v1/claims` y el reuso de imágenes va
directo por el pipeline).
- **Los dos endpoints se quedan**, a pedido: sirven para probar por Postman, que es justo lo que se
  está haciendo ahora. No son código muerto si el plan de pruebas los usa — pero **no tienen
  consumidor en la app**, y eso hay que saberlo antes de tocarlos.
- **Se sacó la entrada `/api/v1/classifications` de `proxy.conf.json`**: solo servía al dev server
  de Angular y ningún componente la llama. Postman pega al backend directo (8082), así que no le
  hace falta.

### Menores (no defectos, limpieza)
- **Comentario obsoleto** `Case.java` ("classification-service doesn't call rules-service yet, still
  MockRulesAdapter"): falso desde D3/D4 (`RulesRestAdapter` es `@Primary`).
- **History repos write-only**: `InsurerRuleHistoryRepository` y `ScoringConfigurationHistoryRepository`
  solo reciben `save(...)`, ningún lector — si la auditoría debía ser consultable, falta el read.
- ~~**D19 son DOS adapters**: `InsurerDatabaseAdapter` existe en cases-service **y** en
  classification-service; ambos leen el schema bare `aseguradora`. Migrar los dos.~~ → hecho
  (10/08), los dos migrados.

### Estado
| ID | Sev | Dueño |
|----|-----|-------|
| D21 (scoring → motor), D22 (score/breakdown en la UI del analista) | Alto | ✅ Resuelto (10/08), sin validar en vivo |
| D23 (sector/fechaIngreso), D24 (aceptar/modificar), D25 (suma asegurada), D26 (filtro bandeja) | Medio | ✅ Resuelto (10/08), sin validar en vivo |
| D27 (PolicySnapshot) | Medio | ✅ Escritura resuelta (10/08), sin validar en vivo — la lectura queda como enhancement de la vista del analista |
| D28 (filas muertas) | Bajo | ✅ Resuelto (10/08) — `PEP` llena, las otras cuatro filas borradas; `Tomador`/`N° de certificado` vuelven solo si se modelan pólizas colectivas |
| D29 (columnas cases) | Bajo | ✅ `scoring_configuration_id` resuelto (10/08); `fraud_determined`/`destination` movidas a §5 (falta decisión de negocio) |
| D30 (endpoints sin uso) | Bajo | ✅ Cerrado (10/08) — los endpoints se quedan para Postman, se sacó la entrada muerta de `proxy.conf.json` |
