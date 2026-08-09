# Handoff — Reglas de negocio (Fast Track del referente)

Documento para retomar el trabajo de **reglas de negocio** con otro Claude, sin contexto
previo. Fecha: 2026-08-07. Branch: `feature/backend-reglas-de-negocio`.

> Antes de tocar nada, leé también:
> - `docs/decisiones-reglas-a-validar.md` — decisiones tomadas y abiertas (D1-D6).
> - Las memorias `reglas-fast-track-eje-cobertura-der` y `reglas-frontend-backend-estado`.
> - El `CLAUDE.md` raíz (arquitectura, vocabulario de dominio, design system).

---

## 1. De qué va esto

El referente de una aseguradora tiene que poder **configurar las reglas de negocio de su
compañía** desde una pantalla, sin redeploy (decisión de arquitectura #12: reglas dinámicas
en BD). El foco de estas sesiones fue el **Fast Track**: un gate determinístico que agiliza
un siniestro (nunca lo resuelve solo — siempre hay analista, decisión #5).

**Fuente de verdad del modelo de datos: el DER** (`docs/arbiter der.mdj`, StarUML/JSON), por
encima de cualquier mock. Se parsea con Python (`json.load`) buscando `_type == "ERDEntity"`.

### El eje que importa (no repetir el error)
- `regla_aseguradora` (entidad `InsurerRule`) se scopea por **`rama_id` (NOT NULL) +
  `cobertura_id` (NULLABLE)**. El eje de una regla es la **cobertura**. `cobertura_id` null ⇒
  regla por ramo (válida en el DER).
- **NO** tiene `hecho_generador_id`. Inferir el modelo desde el `MockRulesAdapter` (que keyea
  por hecho generador) lleva a proponer agregar `claim_cause_id` → contradice el DER.
- `requisito_documental` (agenda documental) sí va por `rama_id + hecho_generador_id`. Ejes
  paralelos a propósito: reglas por cobertura, requisitos documentales por hecho generador.

---

## 2. Qué hicimos (cronológico, alto nivel)

Sesión previa (backend + primer UI, commits `629915f`, `6047dc7`):
1. **Transporte de la cobertura**: `ClaimReport` (common-lib) perdía la cobertura camino a
   classification. Se agregó `Long coverageId`. `CaseServiceImpl` la toma de la póliza;
   `ClassificationServiceClient` la mete en el `ClaimReport`.
2. **Motor keyea por cobertura**: `RulesAdapter.getRules(branchId, coverageId, claimCauseId)`.
   `RulesRestAdapter` (@Primary) lee `GET /api/v1/rules/internal/fast-track?coverageId=` de
   rules-service con **service-token** (JWT sin rol, con `tenantSchema`) y superpone los
   umbrales sobre el baseline del `MockRulesAdapter`. Best-effort: si rules-service se cae,
   cae al mock y no rompe la clasificación.
3. **Backend Fast Track en rules-service** (nuevo): `FastTrackRuleController`
   (`/api/v1/rules/branches`, `/fast-track` GET+PUT, `/internal/fast-track`),
   `FastTrackRuleService.upsert` (persiste `InsurerRule.configuration` JSONB + snapshot en
   `insurer_rule_history`, append-only), `FastTrackConfigDto` (4 campos), excepciones +
   `@RestControllerAdvice`. `CoverageController` en cases-service
   (`GET /api/v1/coverages?branchId=`).
4. **Primera UI (mía)**: solapa Fast-Track-only por cobertura → **reemplazada** al integrar la
   branch `feature/front-reglas` (UI ramo-céntrica, más rica, de un compañero).

Esta sesión (commit `4f9c8b3`):
5. **Franquicia / plazo de denuncia / tope anual: de nivel ramo → nivel cobertura**, fiel al
   DER (`cobertura.franquicia` / `plazo_denuncia_horas` / `tope_eventos_por_anio`). Se eliminó
   la tarjeta "Parámetros del ramo"; esos campos ahora se editan en cada cobertura. Confirmado:
   **no hay tope de suma asegurada por cobertura** en el DER (vive en `poliza`).
6. **Solapa Fast Track cableada al backend real** (ver sección 4). Antes toda la pantalla
   guardaba en un mock en memoria.
7. **`docs/decisiones-reglas-a-validar.md`** (D1-D6) y actualización de memorias.

---

## 3. Estado actual — qué anda y qué es mock

| Pieza | Estado |
|---|---|
| Backend Fast Track persiste (PUT → `regla_aseguradora` + auditoría) | ✅ hecho y probado (curl) |
| Backend lee Fast Track para el gate determinístico (`FastTrackValidator`) | ✅ hecho y probado |
| **Solapa Fast Track del front** → persiste en backend | ✅ implementado, **compila**, NO probado en vivo |
| Otras 4 solapas del front (Coberturas, Documentación, Scoring, Reglas) | ❌ mock en memoria |
| Reglas/exclusiones en **texto** que alimentan el prompt del LLM | ⚠️ el mecanismo corre, pero salen del **mock**, no del referente |
| `resultado_regla` / `RuleResult` (auditoría 1..* del DER) | ❌ el repo existe, nadie lo escribe |

### Arquitectura del sistema (recordatorio)
5 servicios Spring Boot: `auth`(8080; local override 8090), `rules`(8081),
`classification`(8082), `cases`(8083), `reports`(8084) + `common-lib` + `arbiter-frontend`(4200).
Multi-tenant por esquema (JWT `tenantSchema` → filtro → ThreadLocal → connection provider).
Login es **Auth0 real**. Roles: `ASEGURADO`, `ANALISTA_SINIESTROS`, `REFERENTE_ASEGURADORA`.

### Datos reales sembrados (`db/init-multitenant.sql`)
- Ramas (`arbiter_common.branch`): `1=Celulares`, `2=Hogar`, `3=Tecnología Portátil`.
- Coberturas de Celulares (por esquema de tenant): `1=Robo de celular`, `2=Hurto`.
- Todas las pólizas BBVA linkean a `coverage_id=1`.

---

## 4. El cableado de la solapa Fast Track (detalle, commit `4f9c8b3`)

Archivos del front (`arbiter-frontend/src/app/`):
- **`features/admin/fast-track-rules.service.ts`** (nuevo) — HttpClient real:
  `listCoverages(branchId)`, `getFastTrack`, `saveFastTrack`, y los combinados
  `loadForBranch` / `saveForBranch` (fan-out). El auth-interceptor adjunta el JWT solo.
- **`features/admin/reglas/reglas.component.ts`** — inyecta el service; `saveFastTrack()`
  (botón propio), `loadFastTrackFromBackend()` al seleccionar ramo, mapeo UI↔DTO.
- **`.html` / `.scss`** — botón "Guardar Fast Track" + nota de campos diferidos + estilo.
- **`features/admin/rules-config.service.ts`** — mock; se le alinearon los ids a los branchId.
- **`core/models/business-rules.ts`** — franquicia/plazo/tope movidos a `Coverage`.

Decisiones de diseño (todas en `decisiones-reglas-a-validar.md` D6):
- **`RamoRules.id = branchId` real.** Los nombres del mock no matcheaban las ramas
  ("Celular Protegido" ≠ "Celulares"), así que se alinearon ids: `'celular'→'1'`,
  `'tecnologia'→'3'`. Ramo con id no numérico (alta nueva) no persiste → mensaje explícito.
- **Fan-out per-cobertura.** La UI configura Fast Track por ramo; el motor lee por cobertura
  (`getByCoverage`). Al guardar, se escribe la misma config a TODAS las coberturas del ramo.
  Al cargar, se lee de la primera. **A validar:** ¿un Fast Track por ramo, o distinto por
  cobertura? El DER banca ambos.
- **Campos que persisten** (los que el `FastTrackValidator` evalúa hoy): `maxClaimedAmountRatio`,
  `maxPriorClaims`, `requiresUpToDatePolicy`, `requiredDocumentTypes`.
- **Campos diferidos** (la UI los tiene pero NO se envían al back): `minPolicyAgeMonths`,
  `priorClaimsWindowMonths`, `criteria` (texto). La pantalla lo aclara con una nota.
- **`enabled=false`** ⇒ se guarda config vacía (todo null) = sin Fast Track.

**Importante:** el Fast Track **NO alimenta el prompt** del LLM (es un gate ANTES del LLM, lo
saltea). Lo que va al prompt son los textos `rules`/`exclusions`/`fastTrackCriteria`
(`BusinessRules` → `PromptBuilder.renderRulesAndPolicy`), y ESOS todavía salen del mock.

---

## 5. Qué falta (pendientes, priorizados)

### P1 — Probar la solapa Fast Track en vivo (lo más inmediato)
Está implementada y compila, pero **nunca se corrió**. Levantar stack (rules:8081,
cases:8083, front:4200), loguearse como REFERENTE, ir a **Reglas → Fast Track**, guardar, y
verificar el `PUT` → `regla_aseguradora` + que el gate lo lea en una clasificación real.

### P2 — Cablear los textos del referente al prompt del LLM
Hoy `RulesRestAdapter` solo superpone los **umbrales** de Fast Track; `rules`/`exclusions`/
`fastTrackCriteria` (lo que va al prompt) vienen del `MockRulesAdapter`. Falta:
1. rules-service persiste/sirve esas listas de texto (JSONB por ramo/cobertura).
2. `RulesRestAdapter` las superpone en `BusinessRules.rules/.exclusions/.fastTrackCriteria`.
Con eso, lo que el referente escribe en las solapas **Coberturas** (exclusiones) y **Reglas de
negocio** (texto) llega de verdad al prompt.

### P3 — Cablear las otras solapas (una por vez, endpoint por concern)
El plan acordado es **un endpoint por solapa** (no un `/rules/ramos` monolítico), reusando lo
que ya existe. Mapeo a tablas del DER:
- **Scoring** → `configuracion_scoring` + `peso_factor` + `configuracion_banda` (tiene tablas
  propias, mapea limpio). Buen próximo candidato.
- **Documentación** → `requisito_documental` (ojo: DER keyea por rama **+ hecho generador**;
  la UI lo tiene plano por ramo — ver D5).
- **Coberturas** → `cobertura` (nombre, franquicia, plazo, tope) + exclusiones/cláusula/suma
  en JSONB o como se decida (D3, D4).
- **Reglas de negocio** (texto) → JSONB; converge con P2 (van al prompt).
- **UX:** cada solapa con su propio "Guardar" (como Fast Track), no el "Guardar cambios" global.
- **Swap del mock:** `RulesConfigService` (mock) → HttpClient. Ya devuelve `Observable`, los
  componentes no se tocan.

### P4 — Auditoría `resultado_regla` (exige el DER, Disposición 2/2023)
`expediente → resultado_regla` es 1..\*: cada evaluación de regla debe dejar rastro ligado a la
versión de la regla. El repo/entidad existe pero **nadie lo escribe**. Falta que
`FastTrackValidator` (y futuras evaluaciones) escriban un `RuleResult` por evaluación.

### P5 — Cerrar campos diferidos del Fast Track
`minPolicyAgeMonths`, `priorClaimsWindowMonths`: extender `FastTrackConfigDto` +
`BusinessRules.FastTrackThresholds` + enforcement en `FastTrackValidator`
(`minPolicyAgeMonths` es fácil: `policy.effectiveFrom`; la ventana necesita conteo de
siniestros datado). `historial_regla_aseguradora.changedBy` queda null (el JWT trae email, no
el id de referente; el actor va en `reason` hasta que se resuelva el lookup email→referente).

### Decisiones abiertas a validar con el equipo (de `decisiones-reglas-a-validar.md`)
- D2: plazo de denuncia en **días** (UI) vs **horas** (DER) — ¿backend convierte?
- D3: cláusula / exclusiones-texto / reglas-texto → ¿JSONB o tabla propia?
- D5: agenda documental por ramo (UI) vs ramo+hecho generador (DER).
- D6: Fast Track por ramo (fan-out) vs por cobertura.

---

## 6. Cómo levantar y probar

- Stack local en Docker (ver memoria `arbiter-levantar-stack-local`): el stack "bueno" vive en
  `develop`; se levanta sin el contenedor de qwen (`--no-deps`); auth queda en **8090** y hay
  un password de dev. Un reset total de la BD borra el volumen y re-corre `db/init-*.sql`.
- Front: `cd arbiter-frontend && npm start` → http://localhost:4200. El `proxy.conf.json` ya
  rutea `/api/v1/rules`→8081, `/api/v1/coverages`→8083, `/api/v1/auth`→8090.
- Probar rules-service directo: mintear un JWT HS512 (secret default en `application.yml` /
  `docker-compose.yml`, `JwtSupport.key(secret)`), con claim `rol=REFERENTE_ASEGURADORA` y
  `tenantSchema` del tenant, y pegarle a `PUT /api/v1/rules/fast-track?branchId=1&coverageId=1`.
- **Nota de entorno:** los tests JUnit por CLI daban "Tests run: 0" (problema de discovery en
  este entorno); se validó por compilación + curl E2E.

---

## 7. Convenciones y cuidados (del CLAUDE.md / memorias)

- **Español rioplatense** en commits/docs; **inglés** en identificadores y comentarios de código.
- **Design system**: usar el kit (`app-button`, `app-card`, `app-input`, `app-badge`, …) y
  tokens semánticos; **prohibido hex/px crudos** fuera de `_tokens.scss`. Ver memoria
  `design-system-frontend`.
- **No filtrar config local al repo** (memoria `no-filtrar-config-local-al-repo`): stagear
  **archivo por archivo**; dejar SIEMPRE afuera `docker-compose.override.yml`, `get-docker.sh`,
  `arbiter-frontend/src/styles/_breakpoints.scss`.
- **Commitear solo cuando la usuaria (Valentina) lo pida.** Cerrar el mensaje con
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- **Enum literals en inglés**; el label español es cosa del frontend.
- Comunicación entre módulos backend: REST interno con DTO en `common-lib` (`RestClient`, no
  `RestTemplate`).

---

## 8. Punteros rápidos

- Commit del cableado Fast Track: **`4f9c8b3`**.
- DER: `docs/arbiter der.mdj`. Decisiones: `docs/decisiones-reglas-a-validar.md`.
- Backend Fast Track: `rules-service/.../controllers/FastTrackRuleController.java`,
  `.../services/FastTrackRuleService.java`.
- Motor: `classification-service/.../adapters/RulesRestAdapter.java`,
  `.../services/FastTrackValidator.java`, `.../services/PromptBuilder.java`.
- Front: `arbiter-frontend/src/app/features/admin/` (`reglas/`, `fast-track-rules.service.ts`,
  `rules-config.service.ts`), `core/models/business-rules.ts`.
