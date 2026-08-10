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
| 4 | **Fraude: constancia IMEI fabricada** | Constancia inventada, no reusada, IMEI que no coincide | 🔴 Nada la detecta (**D4b**). Va como caso en rojo. |
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
- **D4b**: `DocumentInconsistencyEvaluator` (el factor que agarraría "el IMEI del documento no coincide
  con el del bien") es un **stub** que se declara no evaluable.
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
- ⚠️ **Queda abierto**: el modelo de **clasificación no recibe la imagen**, solo su OCR — el modelo
  de visión la ve en la extracción, pero la clasificación es texto puro. Para casos de fraude
  documental *visual* (constancia fabricada) esto limita lo que el LLM puede notar.

**D19 · El `InsurerDatabaseAdapter` no se migró a multi-tenant** — *detectado 10/08 (Mar)*
Lee el schema **bare `aseguradora`** (`InsurerDatabaseAdapter.java:35`), que es del modelo
single-schema viejo (`db/datos-aseguradoras.sql`), **no** los `aseguradora_<tenant>` que crea el seed
multi-tenant (`init-multitenant.sql` → `create_insurer_db_schema('aseguradora_bbva'…)`). Encima la
query hace `JOIN … c.id = p.aseguradora_id` (`:60`), pero en el modelo multi-tenant la `poliza` ya
**no tiene** `aseguradora_id` (el discriminador se dropeó; `compania` es una sola fila por schema).
Consecuencia: tras un reseed multi-tenant limpio (reset→init→seed, **sin** `datos-aseguradoras.sql`),
`/policies` y `/cases` tiran **500** (`relation "aseguradora.poliza" does not exist`). Hoy solo anda
si sobrevive el schema `aseguradora` viejo; los `aseguradora_bbva`/`aseguradora_provincia` que llena
el seed multi-tenant **no los lee nadie**.
- **Fix**: migrar el adapter a leer `aseguradora_<tenant>` según el `TenantContext` (la aseguradora
  correspondiente al asegurado) y reescribir la query — sacar `p.aseguradora_id`, `compania` es la
  fila única del schema. A decidir: la vista multi-aseguradora (Martina es cliente de BBVA y
  Provincia con el mismo DNI, §6.2) — ¿solo el tenant actual, o unir todos los `aseguradora_*`?
- **Workaround hasta migrarlo**: después del reset trio, correr también `db/datos-aseguradoras.sql`
  (recrea el schema `aseguradora` bare que el adapter todavía necesita).

### 🟡 Medios

**D9 · Cuatro columnas de `coverage` que no lee nadie**
`waiting_period_days` (carencia), `claim_exhausts_coverage`, `covers_family_group`, `is_individual`:
cero consumidores, ni siquiera para mostrarlas.

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
- **Queda pendiente la evaluación**, que entra con [plan-reglas-evaluables.md](plan-reglas-evaluables.md):
  `coverage.report_deadline_hours` comparado contra `police_report_at - occurred_at`, escribiendo
  `rule_result`. Es una de las "duras-eables" que menciona D4a.
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

**D14 · Criterios de Fast Track que la UI promete y el motor no tiene**
`minPolicyAgeMonths` y `priorClaimsWindowMonths` están en el modelo del front
(`core/models/business-rules.ts`, `FastTrackConfig`) y en la solapa Fast Track, pero **no existen**
en `FastTrackConfigDto` ni en `BusinessRules.FastTrackThresholds` (4 campos), y `saveFastTrack()` no
los manda. Un caso tipo "póliza con menos de 6 meses → no aplica Fast Track" hoy no se evalúa. Suma
lo mismo la lista **`criteria`** (criterios descriptivos): es editable en la UI y `saveFastTrack()`
la omite (queda en el draft, no viaja al backend).

**D15 · El panel de ramos salía de un mock** — ✅ **RESUELTO (10/08)**
La lista de ramos sale del catálogo real (`GET /api/v1/rules/branches`, tabla `branch`) vía
`BranchesService` — se eliminó el `SEED_RAMOS`/`RulesConfigService` del front. Se sumó el **CRUD
completo**: `BranchController` (rol REFERENTE) con `POST` (alta), `PUT /{id}` (renombre) y
`DELETE /{id}` (baja), servidos por `BranchCatalogService` (nombre único → 409; baja con guarda de
referencias → 409 si el ramo tiene hechos generadores/coberturas/reglas). El front re-habilitó alta,
renombre y baja apuntando a esos endpoints.
- **Ojo (diseño)**: `branch` es un catálogo **global** (`arbiter_common`, compartido por todas las
  aseguradoras), no una config por aseguradora — crear/borrar un ramo toca el catálogo maestro. Si en
  el futuro se quiere que cada aseguradora tenga su propio catálogo, hay que mover/rediseñar `branch`.
- **Test**: `BranchCatalogServiceTest`.

### 🔵 Bajos

**D16 · PEP se pide, se guarda y no lo usa nadie**
Ni scoring, ni prompt, ni pantalla del analista. Cero consumidores.

**D17 · La advertencia "los pesos deberían sumar 100%" contradice al motor**
`RiskScoringService` normaliza (`score = weightedSum / totalWeight`): los pesos son **relativos**. El
seed suma 190% y la UI lo marca como error de fábrica. *(Detectado en la sesión anterior, no
re-verificado ahora.)* Decidir con Mar/Valen: sacar la regla o ajustar el seed.

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
| D4b | Alto | Abierto — depende de H0007 (OCR estructurado) | — |
| D5 | Alto | fecha/monto/lugar ✅ (v2, sin validar en vivo); falta la imagen al LLM | Mar |
| D19 | Alto | Abierto | Mar (rumbo definido) |
| D10, D11, D13 | Medio | ✅ Resuelto (10/08, backend) — reglas duras temporales (`TemporalRuleEvaluator`), bloquean Fast Track; **sin auditar en `rule_result`** y **sin validar en vivo** | Aylén |
| D9, D14 | Medio | Abierto | — |
| D12 | Medio | captura ✅ (Aylén); evaluación del plazo policial pendiente | — |
| D15 | Medio | ✅ Resuelto (10/08) — lista real + CRUD de ramos (`BranchController`) | Aylén |
| D16, D17 | Bajo | Abierto | — |
| D18 | Bajo | ✅ Resuelto (09/08) — reactor completo verde, 334 tests | Aylén |
| **D21–D30** | Alto→Bajo | **Abierto — relevo de desconexiones back↔front (10/08), ver §9** | — |

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
  (Google Vision). Un documento *fabricado* no dispara ninguno de los dos.
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
   roles sobre los endpoints de `CaseController` (`@PreAuthorize` por endpoint).
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

**D21 · El scoring de fraude que configura el referente NUNCA llega al motor**
`ScoringConfigurationController` sirve `GET|PUT /api/v1/rules/scoring` y persiste
`ScoringConfiguration` + `FactorWeight` + `ScoreBand` (el front los edita en `ScoringConfigComponent`).
Pero **no existe `/internal/scoring`** y `RulesRestAdapter` **no overlaya el scoring**: `RiskScoringService`
lee `BusinessRules.scoringConfig()`, que solo lo setea el `MockRulesAdapter`
(`DEFAULT_SCORING_CONFIG`). El propio comentario lo admite (`RulesRestAdapter` javadoc: "The scoring
config still comes from the mock"). Consecuencia: **todo el panel de scoring del referente es
decorativo** — los pesos/bandas/factores que carga no cambian ni una corrida. Es más profundo que D17
(que es solo la advertencia de UI de los pesos). Ligado: `cases.scoring_configuration_id` nunca se
escribe (no hay id de config que auditar). **Fix**: endpoint `/internal/scoring` + `overlayScoring`
en `RulesRestAdapter`, mismo patrón que Fast Track / coverage-limits.

**D22 · El `riskScore` numérico y el `riskBreakdown` se calculan y viajan, pero el front no los muestra**
`CaseResponse` expone `riskScore` (Double) y `riskBreakdown` (aporte por factor + `rationale`,
`RiskBreakdownItem`), pensados "for the analyst's fraud-gauge". El front (`core/models/expediente.ts`)
**no declara ninguno de los dos**: solo usa `riskBand`, y el `FraudGaugeComponent` es categórico (4
bandas, sin número). Grep confirma cero referencias a `riskScore`/`riskBreakdown` en el front.
Consecuencia: el analista ve "Alto/Bajo" pero nunca el score ni **qué factor lo empujó** — el trabajo
del motor de scoring queda invisible. (Distinto del `forensicReport` de imágenes, que sí se muestra.)
Junto con D21: el scoring está desconectado en las dos puntas (config no entra, salida no se muestra).

### 🟡 Medios

**D23 · Alta de usuario: `sector` (obligatorio) y `fechaIngreso` se descartan en silencio**
El front manda `sector` + `fechaIngreso` (`user-admin.service.ts`) y el form **exige `sector`**
(`alta-usuario.component.ts`), pero el `CreateUserRequest` del back (auth-service) solo tiene
`email/nombre/apellido/rol` → Jackson los ignora y **nunca se persisten**. Al revés, `UserResponse`
del back no devuelve `sector`/`fechaIngreso`, así que las columnas **Sector** y **Fecha de ingreso**
de la grilla salen siempre "—". Mismatch en los dos sentidos. (Auth estaba marcado "no revisado" en
§1; esto es concreto.)

**D24 · "Aceptar / Modificar" la clasificación sugerida (vista analista) no persiste**
`expediente-detail.component` — `acceptClassif()` / `modifyClassif()` solo setean una señal local y
muestran "✓ Clasificación aceptada" / "Marcada para modificar", pero **no pegan a ningún backend**: no
hay endpoint, no queda auditoría, se pierde al recargar. La UI aparenta una acción que no ocurre.
(Distinto de APPROVE/REJECT, que sí van a `/decision`.)

**D25 · "Suma asegurada" por cobertura: input editable que nunca se persiste ni se carga**
Solapa Coberturas — el input "Suma asegurada ($)" está vivo y editable, pero `overlayCoverages` lo
setea **siempre en `null`** y `toCoverageRequest` **no lo manda**. El referente lo escribe, se
descarta al guardar y vuelve vacío al recargar. `CoveragesRulesService` documenta que a propósito no
hay campo en el DER (la suma vive en la póliza) — entonces el input **sobra** y confunde. Decidir:
sacarlo, o mostrarlo read-only desde la póliza.

**D26 · Filtro "Tipo de siniestro" de la bandeja: catálogo hardcodeado (con valores inexistentes)**
`bandeja.component.ts` — `claimCauseOptions` es una lista fija de 4 (`Robo en vía pública`, `Hurto`,
`Rotura accidental`, `Siniestro general`); los dos últimos **no existen** en el catálogo real (mismo
problema que el wizard viejo, §2.2). El endpoint real ya existe (`GET /claim-causes` /
`GET /rules/claim-causes`) y el wizard ya migró — la bandeja quedó con el mock, así que filtrar por
esas opciones da resultados vacíos/incompletos.

**D27 · `PolicySnapshot`: entidad con cero escritores y cero lectores**
`PolicySnapshot` + `PolicySnapshotRepository` no se inyectan en ningún lado y `Case.policySnapshot`
(FK) nunca se setea (el javadoc lo admite). La "foto" de la póliza al momento de la denuncia —que el
scoring leería para `policy_standing`/`claim_frequency` y que pide la reproducibilidad de la Disp. SSN
2/2023— **no se guarda**. Análogo a `Notification`/`Metric` (§5) pero no estaba documentado.

### 🔵 Bajos

**D28 · Filas muertas en la grilla del expediente (analista)**
`expediente-detail.component.ts` hardcodea `value: null` permanente en `Canal de origen`, `N° de
certificado`, `Tomador` y `Hecho generador` → siempre "Sin datos". Ojo: `Hecho generador` duplica a
`Causa` (que sí trae `claimCause`) — uno vivo y otro muerto.

**D29 · Columnas de `cases` sin uso**
`fraud_determined` y `destination` (`Case.java`): sin writer ni reader — no hay flujo "marcar como
fraude" ni "derivar" del analista (solo aprobar/rechazar). Más `scoring_configuration_id` (ligado a
D21). Análogo a D9 pero en `cases`.

**D30 · Endpoints sin consumidor (solo Postman/PoC)**
`ClassificationController` (`POST /api/v1/classifications`, `GET /classifications/results`, "isolated
testing") e `ImageEmbeddingController` (`POST /api/v1/image-embeddings/check-duplicate`, "(PoC)") no
los llama nadie (ni front ni back — el flujo real entra por `/api/v1/claims` y el reuso de imágenes va
directo por el pipeline). `proxy.conf.json` rutea `/classifications` al pedo. Muertos o de test.

### Menores (no defectos, limpieza)
- **Comentario obsoleto** `Case.java` ("classification-service doesn't call rules-service yet, still
  MockRulesAdapter"): falso desde D3/D4 (`RulesRestAdapter` es `@Primary`).
- **History repos write-only**: `InsurerRuleHistoryRepository` y `ScoringConfigurationHistoryRepository`
  solo reciben `save(...)`, ningún lector — si la auditoría debía ser consultable, falta el read.
- **D19 son DOS adapters**: `InsurerDatabaseAdapter` existe en cases-service **y** en
  classification-service; ambos leen el schema bare `aseguradora`. Migrar los dos.

### Estado
| ID | Sev | Dueño |
|----|-----|-------|
| D21 (scoring no llega al motor), D22 (score/breakdown no se muestra) | Alto | — |
| D23 (sector/fechaIngreso), D24 (aceptar/modificar), D25 (suma asegurada), D26 (filtro bandeja), D27 (PolicySnapshot) | Medio | — |
| D28 (filas muertas), D29 (columnas cases), D30 (endpoints sin uso) | Bajo | — |
