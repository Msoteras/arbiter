# Plan — Reglas duras evaluables + auditoría en `rule_result`

**Fecha:** 09/08/2026 · **Rama:** `feature/fix-defectos-aylen` · **Estado:** ✅ **implementado (10/08)** —
pasos 1–5 y **7 (UI del referente)**. Decisión §2: se hizo primero la **(a)** (backend + seed) y luego
se sumó la **(b)** conectando la pantalla de reglas existente. **Pendiente:** paso 6 (sacar las
exclusiones del prompt + `classification-v3`) y el corte degradado del paso 3. **Sin validar en vivo.**

**Cierra:** D3, D4a (la parte de exclusiones), D4c del
[handoff de pruebas y defectos](handoff-pruebas-y-defectos.md).
**Deja afuera a propósito:** D4b (ver §7).

Es la historia #1 de la lista de candidatas del handoff (§7), la que ahí figura como "la más valiosa".

> **Cómo quedó (10/08).** `ClaimReport` lleva `claimCauseId` (match por id, no por nombre).
> rules-service sirve `GET /internal/evaluable?coverageId` (`InternalEvaluableRuleService` +
> `EvaluableRuleController`), leyendo `insurer_rule` de tipo `COVERAGE_EXCLUSION` con
> `configuration = {"excludedClaimCauseIds":[…]}`. `RulesRestAdapter.overlayEvaluableRules` lo
> compone sobre el baseline; `BusinessRules` gana `evaluableRules`. `CoverageRuleEvaluator` corre
> **antes** del gate de Fast Track: una exclusión bloquea el Fast Track y deriva a
> `LLM_SOLICITA_REVISION_MANUAL` sin LLM (no cierra el expediente). `ClassificationResultsService`
> escribe `rule_result` (PASS y FAIL) cuando hay `caseId`. Seed del caso 6 en `init-multitenant.sql`.
> Tests: `CoverageRuleEvaluatorTest`, `ClassificationResultsServiceTest` (escritura) y caso 6 en
> `ClassificationOrchestratorIntegrationTest`. Reactor completo verde (342 tests).

---

## 1 · Los tres hallazgos que fijan el diseño

No hay que volver a discutir estos puntos: el modelo de datos y el código existente ya los deciden.

### 1.1 `rule_result.rule_id` es FK NOT NULL a `insurer_rule`

```
rule_id  BIGINT NOT NULL REFERENCES %I.insurer_rule(id)
```
(`db/init-multitenant.sql`, tabla `rule_result` del template de tenant)

**Consecuencia:** una regla hardcodeada en código **no se puede auditar**, porque no hay fila a la
que apuntar. Como el objetivo de D4c es el registro que pide la Disposición SSN 2/2023, toda regla
evaluable **tiene que existir como fila de `insurer_rule`**. Esto descarta modelar las exclusiones
como columna suelta (p.ej. estructurar `coverage.exclusions`): rompería el FK de la auditoría.

Es el argumento que zanja el punto que
[decisiones-reglas-a-validar.md §D3](decisiones-reglas-a-validar.md) dejó abierto
(*"si alguno de estos debe modelarse prolijo porque el motor lo va a consumir"*). Sí, y va a
`insurer_rule`.

### 1.2 No existe relación `coverage` ↔ `claim_cause`

`coverage` tiene `branch_id` y un `exclusions JSONB` que hoy guarda **texto libre**; `claim_cause`
está scopeada solo por `branch_id`. O sea: hoy nada en el modelo sabe qué hechos generadores cubre
una cobertura, que es literalmente lo que D3 reclama.

Estado actual de lo que escribe el referente (por §D3 de `decisiones-reglas-a-validar.md`, "Camino C"):
`commonExclusions` y `businessRules` se persisten como `InsurerRule` con
`rule_type='EXCLUSIONS'`/`'BUSINESS_RULES'`, `coverage_id` null y `configuration` JSONB = **array de
strings**. De ahí salen a `BusinessRules.exclusions()`/`.rules()` y de ahí al prompt. Nada las evalúa.

### 1.3 Quién evalúa ya está decidido por precedente

`FastTrackValidator` vive en **classification-service** y evalúa `BusinessRules.FastTrackThresholds`
que le sirve rules-service vía `RulesRestAdapter`. El patrón establecido es:

> **rules-service sirve configuración · classification-service evalúa y persiste el resultado.**

Y encaja con dónde están las cosas: `RuleResult` + `RuleResultRepository` ya viven en
classification-service (`models/entities/`, `models/repositories/`), con **cero escritores**.

No hay que inventar contrato inter-módulo nuevo: se extiende el que ya existe.

---

## 2 · ⚠️ La decisión abierta — resolver antes de codear

El referente hoy escribe exclusiones como **texto libre**. Para que sean evaluables tiene que elegir
hechos generadores de una lista, y eso es pantalla nueva en la solapa *Reglas de negocio*.

| Opción | Alcance | Consecuencia |
|--------|---------|--------------|
| **(a)** *recomendada* | Backend + evaluación + `rule_result`. Las reglas entran **por seed** para el caso de prueba del ramo. | Cierra D3 y D4c ya, y habilita la batería de testing. La UI queda como incremento aparte que no reabre el backend. |
| **(b)** | (a) + el CRUD en la pantalla del referente. | Hace demostrable el RNF de mantenibilidad ("ramo nuevo sin tocar código"), pero es bastante más trabajo. |

**Recomendación: (a).** El resto de este plan está escrito para (a); el paso 6 es lo que se agrega si
se elige (b).

---

## 3 · Los pasos

### Paso 1 · `rule_type = 'COVERAGE_EXCLUSION'` con `configuration` estructurada

En `insurer_rule` (tabla que ya tiene todo lo necesario: `rule_type`, `effect`, `priority`,
`blocks_fast_track`, `configuration JSONB`, `branch_id`, `coverage_id` nullable):

```json
{ "excludedClaimCauseIds": [3] }
```

- `coverage_id` **seteado** (la exclusión es de una cobertura concreta, no del ramo).
- `effect = 'RECHAZAR'`, `blocks_fast_track = true`.
- Sin tabla nueva y **sin cambio en el DER**: usa `insurer_rule` como fue diseñada.

Semántica elegida: **lista negra** (qué hechos generadores NO cubre), no lista blanca. Razón: hoy
todos los hechos generadores del ramo son alcanzables, así que la lista negra es un cambio aditivo —
una cobertura sin regla sigue comportándose igual que hoy. Con lista blanca, toda cobertura sin
configurar dejaría de aceptar denuncias, y eso rompe el flujo existente.

Seed para el caso de prueba: la cobertura *Hurto* (id 2) excluyendo… **no** — ojo, ver §8: hay que
confirmar los ids contra Railway antes de escribirlos. El caso del handoff es "Hurto no cubierto"
(caso 6 del §2.4), o sea una cobertura de robo que excluye `Hurto`.

### Paso 2 · `BusinessRules` gana las reglas evaluables

`classification-service/.../dto/BusinessRules.java` — sumar al record:

```java
List<EvaluableRule> evaluableRules   // id, ruleType, configuration (ya parseada), effect, blocksFastTrack
```

El `id` es lo que después va a `rule_result.rule_id`, así que **tiene que viajar**: sin él no hay
auditoría posible. Es el mismo motivo por el que hoy `Case.scoringConfigurationId` queda en null
(ver [[project-classification-no-llama-rules]] / handoff D4c) — no repetir el error.

### Paso 3 · Servirlas desde rules-service

- **Endpoint**: `GET /api/v1/rules/internal/evaluable?coverageId=…`, sin rol (lo consume el motor,
  no un referente) — mismo criterio que el `/internal/fast-track` que ya existe.
- **Adapter**: un `overlayEvaluableRules(...)` en `RulesRestAdapter`, encadenado como los otros tres
  (`overlayFastTrack` → `overlayRuleTexts` → `overlayDocumentRequirements`).
- **Mantener el best-effort**: lectura independiente, `try/catch`, y si rules-service no responde se
  cae al baseline sin tirar abajo la clasificación. Es el contrato que ya documenta el javadoc del
  adapter.

> ⚠️ Con una diferencia importante respecto de Fast Track: si las reglas evaluables **no se pueden
> leer**, el resultado no puede ser "pasa igual". Decidir el modo degradado: lo razonable es no
> escribir `rule_result` y que el caso vaya a `LLM_SOLICITA_REVISION_MANUAL`, nunca aprobar por
> silencio del motor de reglas.

### Paso 4 · `CoverageRuleEvaluator` en classification-service

Nuevo service al lado de `FastTrackValidator` (`classification/services/`), misma forma:

```java
public record Result(boolean excluded, List<Finding> findings) {}
// Finding: ruleId, ruleType, result (PASS/FAIL), evaluatedValue
```

Lo llama `ClassificationOrchestrator` **antes** del gate de Fast Track (una exclusión dura hace
irrelevante al Fast Track). Hoy el orquestador llama a
`fastTrackValidator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules(), …)` en las líneas 51 y
133 — el evaluador nuevo se engancha ahí, sobre el mismo `RiskContext` ya armado.

**Cierra D3.**

### Paso 5 · Escribir `rule_result`

Por cada regla evaluada, una fila (`RuleResultRepository`, hoy sin escritores):

| Columna | Qué va |
|---|---|
| `rule_id` | el id de la `insurer_rule` evaluada (del paso 2) |
| `rule_type` | `'COVERAGE_EXCLUSION'` |
| `result` | `PASS` / `FAIL` |
| `evaluated_value` | qué se comparó, legible (ej. `claimCause=Hurto`) |
| `score_contribution` | null acá (es exclusión, no scoring) |
| `case_id` | el expediente |
| `evaluated_at` | ahora |

Se escribe **tanto el PASS como el FAIL**: la auditoría de la Disposición 2/2023 es "qué regla se
evaluó y con qué resultado", no "qué regla falló". Una tabla que solo tiene rechazos no prueba que
las demás se evaluaron.

**Cierra D4c.**

### Paso 6 · Sacar del prompt lo que pasó a evaluable

`PromptBuilder.renderRulesAndPolicy` hoy pega `rules.exclusions()` y `rules.rules()` como texto. Las
que pasaron a `COVERAGE_EXCLUSION` **no** van más al prompt: quedan solo las genuinamente
interpretativas (relato inconsistente, daño no relacionado con el robo).

En su lugar, inyectar el **resultado ya evaluado** ("la cobertura excluye Hurto: FAIL") para que el
LLM razone sobre un hecho establecido en vez de reinterpretar la regla.

→ Esto obliga a **bumpear el prompt a `classification-v3`** y actualizar `getPromptVersion()`, por el
mismo motivo por el que Mar hizo v2 en D5: `llm_analysis.prompt_version` es parte de la auditoría y
no se puede cambiar el prompt sin versionarlo.

**Cierra D4a** en la parte que corresponde (las duras). El matiz del handoff se mantiene: las
interpretativas siguen y deben seguir en el prompt.

### Paso 7 · UI del referente — ✅ **hecho (10/08)**

Selector múltiple de hechos generadores **por cobertura** en la solapa *Coberturas* (no *Reglas de
negocio*: la exclusión dura vive al lado de cada cobertura, junto a las exclusiones en texto). Se
trabaja **por cobertura**, no por ramo — sin el fan-out de Fast Track, porque una exclusión es por
cobertura por definición.
- **Backend (rules-service)**: `CoverageExclusionController` (rol REFERENTE) con
  `GET/PUT /coverage-exclusions?coverageId` (+`branchId` en el PUT) y `GET /claim-causes?branchId`
  (id + nombre, para el selector). `CoverageExclusionRuleService` hace el upsert de la fila
  `COVERAGE_EXCLUSION` con snapshot de historial, igual que Fast Track.
- **Front**: `CoverageExclusionsService` + chips de hechos generadores por cobertura en
  `reglas.component`. Se guardan dentro de "Guardar coberturas" (solo para coberturas ya
  persistidas; una recién creada las configura tras el reload). El **alta de ramos se deshabilitó**
  (no hay CRUD de Branch; el catálogo de ramos lo fija el seed).
- **Test**: `CoverageExclusionRuleServiceTest` (get/upsert/historial/catálogo).

---

## 4 · Tests

- `CoverageRuleEvaluatorTest` — unitario: excluye / no excluye / cobertura sin reglas (baseline
  intacto) / configuración JSONB corrupta (no debe tirar abajo la clasificación).
- `RuleResultRepositoryTests` (Testcontainers, sufijo `*Tests` — Surefire excluye `*IT`) — que la
  fila se escriba y que el FK a `insurer_rule` se respete de verdad.
- Extender `ClassificationOrchestratorIntegrationTest` con el caso 6 del handoff: Hurto sobre
  cobertura que lo excluye → no llega al LLM, se rechaza por regla y queda la fila de auditoría.
- Regresión: `FastTrackValidatorTest` no debería cambiar. Si cambia, el evaluador nuevo se metió en
  el camino equivocado.

---

## 5 · Cómo correrlo (entorno, verificado el 09/08)

`mvn` **no está en el PATH**. Está en la caché del wrapper:

```bash
C:\Users\User\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd
```

Con `JAVA_HOME=C:\Program Files\Java\jdk-21` (el JDK del sistema resuelve a 26 y rompe Lombok).

**Docker tiene que estar levantado** o todos los tests con Testcontainers fallan en el bootstrap
—`CaseSecurityTest`, `*Tests` de repositorios— y el build igual "corre" sin validar nada. Lección de
hoy: si no arranca Docker Desktop primero, la suite miente.

Como se toca `common-lib` (`BusinessRules` no, pero el DTO compartido sí si se decide moverlo):
`mvn -am verify` sobre los consumidores.

---

## 6 · Riesgos y cosas a no romper

1. **No convertir el motor de reglas en decisor automático.** Una exclusión dura puede fundamentar
   un rechazo, pero **no cierra el expediente**: sigue necesitando la decisión del analista
   (CLAUDE.md, decisión #5 — human-in-the-loop, sin excepción ni para Fast Track). El evaluador
   produce un hallazgo, no una resolución.
2. **Lista negra, no blanca** (paso 1) — con lista blanca, toda cobertura sin configurar deja de
   aceptar denuncias.
3. **El modo degradado no puede ser "aprueba"** (paso 3). Si el motor no contesta, revisión manual.
4. **Versionar el prompt** (paso 6) o se rompe la trazabilidad de `llm_analysis.prompt_version`.
5. **Confirmar los ids del catálogo contra Railway** antes de escribir seeds: Tecnología Portátil
   pasó de branch 3 a **branch 2** en el último reseed (nota operativa del handoff §8).
6. **`db/init-multitenant.sql` se edita a mano** — no hay Flyway, y `ddl-auto` es `validate`. Los ITs
   lo pisan a `update` en `AbstractPersistenceIT`. Si se agrega columna, va al script.

---

## 7 · Por qué D4b queda afuera

D4b es el stub de `DocumentInconsistencyEvaluator` (el factor que detectaría "el IMEI del documento
no coincide con el del bien"). Es de la misma familia que D4a, pero necesita **datos estructurados
del OCR**, que hoy no existen: `OllamaDocumentAnalyzer` devuelve texto libre y nadie lo parsea.

Eso es la brecha de **H0007** (ver [gap-historias-usuario.md §2](gap-historias-usuario.md)), no de
esta historia. Meterlo acá duplica el tamaño y mezcla dos problemas con soluciones distintas.

Consecuencia asumida: **el caso 4 del handoff (constancia IMEI fabricada) sigue en rojo** después de
este trabajo. Está bien — el handoff ya lo tiene documentado como caso de prueba en rojo.

---

## 8 · Primer movimiento de mañana

1. Resolver la decisión de §2 — (a) o (b).
2. Confirmar los ids reales de `branch` / `claim_cause` / `coverage` contra Railway.
3. Paso 1 (el `rule_type` nuevo + seed) y paso 4 (el evaluador), que es el corazón y se puede probar
   en unitarios sin levantar nada.
