# Reglas de negocio — decisiones a validar

Registro de decisiones tomadas al integrar la pantalla de reglas del referente
(`arbiter-frontend`, modelo `RamoRules`) con el backend, cuando el modelo de la UI
y el **DER** (`docs/arbiter der.mdj`, fuente de verdad) no coincidían.

Estado: **a validar con el equipo.** Cada punto anota qué se hizo y qué queda abierto.

## Update 2026-08-07 · las 5 solapas ya persisten contra el backend real

Se cablearon las 4 solapas que faltaban (Coberturas, Documentación, Scoring, Reglas de
negocio), cada una con su propio botón "Guardar X", mismo patrón que Fast Track:

- **D1 y D2 resueltas**: `Coverage` (cases-service) ya expone `PUT /api/v1/coverages/{id}`
  (+ POST/DELETE). El service convierte días (UI) ↔ horas (`report_deadline_hours`, D2-a).
- **D3 resuelta (Camino C)**: `clause` y `exclusions` de `Coverage` se agregaron como
  columnas propias (`clause` varchar, `exclusions` JSONB) — no un blob genérico. Las dos
  listas de texto sin tabla (`commonExclusions`, `businessRules`) se persisten como
  `InsurerRule` con `rule_type='EXCLUSIONS'` / `'BUSINESS_RULES'`, `coverage_id` null
  (regla a nivel ramo), `configuration` JSONB = array de strings. Mismo historial
  append-only que Fast Track.
- **D4 confirmada**: `insuredAmount` no se envía al backend, sigue siendo informativo.
- **D5 resuelta por hecho generador**: `document_requirement` se activó tal cual (branch +
  claim_cause) y la pantalla edita **cada hecho generador por separado**, sin fan-out.
  `DocumentRequirementService.upsert(branchId, claimCauseId, …)` escribe solo el hecho
  generador que recibe, y el front tiene su propio selector
  (`reglas.component.ts`, `selectDocClaimCause` / `requiredDocumentsByClaimCause`).
  Ver la sección D5 más abajo.
- **Scoring — decisión nueva, no estaba en D1-D6**: `scoring_configuration` no tenía
  `branch_id` (era una config única por aseguradora); la UI la modela por ramo. Se
  eligió agregar `branch_id UNIQUE` a la tabla (migración chica en
  `db/init-multitenant.sql`) en vez de tratarlo como config única — consistente con
  Fast Track y Documentación, sin sorpresas para el referente.
- **`Coverage.deductible` es la franquicia en puntos porcentuales** (10.00 = 10%), no
  una fracción 0..1 ni un monto — así estaba sembrada (`10.00`, `20.00` en el seed) y
  nada más la leía todavía, así que se mantuvo esa unidad. El service convierte
  ratio (UI, 0..1) ↔ puntos porcentuales (DB) multiplicando/dividiendo por 100.

**Resuelto después**: el alta/baja/rename de `Ramo` (Branch) ya persiste contra el backend —
`BranchController` expone GET/POST `/rules/branches` y PUT/DELETE `/rules/branches/{id}`, y el
front lo consume desde `branches.service.ts`, que reemplazó al mock `RulesConfigService`.

---

## D1 · Franquicia, plazo de denuncia y tope anual pasan a **nivel cobertura**

**Decisión tomada (aplicada en el front).** El modelo del compañero tenía estos tres
campos a nivel **ramo** (`RamoRules.franchiseRatio`, `.reportingWindowDays`,
`.maxAnnualClaims`). El DER los modela a nivel **cobertura**:

| Campo UI (antes, por ramo) | Columna DER (por cobertura) |
|---|---|
| `franchiseRatio` | `cobertura.franquicia` |
| `reportingWindowDays` | `cobertura.plazo_denuncia_horas` |
| `maxAnnualClaims` | `cobertura.tope_eventos_por_anio` |

**Qué se hizo:**
- `Coverage` ahora tiene `reportingWindowDays` y `maxAnnualClaims` (la franquicia ya
  vivía por cobertura como `deductibleRatio`).
- Se eliminaron los tres campos de `RamoRules` y la tarjeta "Parámetros del ramo" de
  la solapa *Reglas de negocio*. Los dos campos nuevos se editan en la tarjeta de cada
  cobertura (solapa *Coberturas*).
- `franchiseRatio` a nivel ramo se descartó por **redundante** con la franquicia por
  cobertura.

**A validar:** que el referente efectivamente quiera configurar estos parámetros por
cobertura y no por ramo (es lo que dicta el DER y las condiciones generales de BBVA,
donde cada cobertura tiene su propia franquicia/plazo/tope).

---

## D2 · Unidad del plazo de denuncia: **días (UI) vs horas (DER)**

**Abierto.** La UI pide el plazo en **días**; el DER lo guarda en **horas**
(`cobertura.plazo_denuncia_horas`). Opciones:

- **(a)** El backend convierte `días × 24 ↔ horas` al mapear. UI en días (más natural
  para el referente).
- **(b)** Cambiar la UI a horas para calcar el DER 1:1.

**Recomendación:** (a) — la conversión es trivial y la UI en días es más legible.
Pendiente de confirmar.

---

## D3 · Campos de la UI **sin respaldo en el DER**

Estos campos existen en la pantalla pero el DER no tiene dónde guardarlos. Hay que
decidir **dónde persisten** (columna JSONB de `regla_aseguradora` / tabla nueva / no
persistir por ahora):

| Campo UI | Situación en el DER | Propuesta |
|---|---|---|
| `Coverage.clause` (código de cláusula, ej. "340") | No hay tabla/columna de cláusulas. | JSONB por ahora. |
| `Coverage.insuredAmount` (suma asegurada tope por cobertura) | La suma vive en `poliza`, **no** hay tope por cobertura. Ver D4. | No persistir como catálogo; sale de la póliza. |
| `Coverage.exclusions` (texto libre por cobertura) | No hay tabla de exclusiones. | JSONB por ahora. |
| `RamoRules.commonExclusions` (texto libre del ramo) | Ídem. | JSONB por ahora. |
| `RamoRules.businessRules` (texto libre) | `regla_aseguradora` existe pero es **estructurada** (tipo/efecto/config), no texto suelto. | JSONB por ahora. |

**A validar:** si alguno de estos debe modelarse "prolijo" (tabla propia) porque el
motor de clasificación lo va a consumir, o si alcanza con guardarlos en JSONB hasta
que se use de verdad. → Es exactamente el criterio del **Camino C (híbrido)**.

---

## D4 · No hay tope de suma asegurada por cobertura en el DER

**Confirmado.** `suma_asegurada` solo existe en `poliza` (y su snapshot
`poliza_consultada`). No hay tope de suma por **cobertura** en el catálogo. El campo
`Coverage.insuredAmount` de la UI queda como informativo / derivado de la póliza del
asegurado, no como dato del catálogo de reglas.

---

## D5 · Agenda documental: la UI la keyea por **ramo**, el DER por **ramo + hecho generador**

**Resuelta: la UI abre el detalle por hecho generador**, se descartó la simplificación por
ramo. La solapa Documentación tiene un selector y guarda solo el hecho generador
seleccionado.

El fan-out estuvo activo un tiempo antes de eso: por eso hay aseguradoras con la misma
lista repetida en todos sus hechos generadores, residuo de esa etapa y no una decisión.

---

## D6 · Solapa Fast Track cableada al backend real (per-ramo → fan-out por cobertura)

**Decisión tomada (aplicada en el front).** La solapa Fast Track dejó de usar el mock y
ahora persiste contra `rules-service` (`FastTrackRulesService`), con su propio botón
"Guardar Fast Track" (guardado por solapa, no el "Guardar cambios" global del mock).

- **`RamoRules.id` = `branchId` real.** Alineé los ids del mock a la base
  (`'celular'→'1'`, `'tecnologia'→'3'`) porque los nombres no matcheaban
  ("Celular Protegido" ≠ "Celulares"). Un ramo con id no numérico (alta nueva mock) no
  puede persistir Fast Track todavía → mensaje explícito.
- **Per-ramo en la UI, per-cobertura en el motor.** El gate lee `getByCoverage(coverageId)`.
  Como la pantalla configura Fast Track por ramo, al guardar hacemos **fan-out**: la misma
  config se escribe a **todas las coberturas del ramo** (`GET /coverages?branchId` →
  `PUT /rules/fast-track` por cada una). Al cargar, se lee de la primera cobertura.
  → **A validar:** ¿está bien "un Fast Track por ramo aplicado a todas sus coberturas", o
  el referente querría un Fast Track distinto por cobertura? (El DER banca ambos.)
- **Campos persistidos vs. diferidos.** El `FastTrackConfigDto` del back tiene 4 campos, y
  son los que el `FastTrackValidator` evalúa hoy: `maxClaimedAmountRatio`, `maxPriorClaims`,
  `requiresUpToDatePolicy`, `requiredDocumentTypes`. La UI además tiene
  `minPolicyAgeMonths`, `priorClaimsWindowMonths` y `criteria` (texto): **no** se envían al
  backend todavía (quedan en el draft). La pantalla lo aclara con una nota. → Cuando se
  quiera enforcement de esos, hay que extender `FastTrackConfigDto` +
  `BusinessRules.FastTrackThresholds` + el validador.
- **`enabled`** se modela como config vacía: deshabilitado ⇒ se guarda todo en `null`, que
  el validador interpreta como "sin Fast Track".

## Lo que SÍ mapea limpio al DER (sin decisión pendiente)

- **Ramo** → `rama`.
- **Fast Track** → `regla_aseguradora` (`tipo_regla=FAST_TRACK`, `configuracion` JSONB).
  Ya funciona punta a punta (referente configura → classification lo lee).
- **Scoring** (factores + pesos + bandas) → `configuracion_scoring` + `peso_factor` +
  `configuracion_banda`.
- **Cobertura**: nombre y franquicia → `cobertura` (`nombre`, `franquicia`).
