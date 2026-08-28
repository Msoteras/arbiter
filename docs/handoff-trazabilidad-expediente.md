# Handoff — H #144 · Trazabilidad completa del expediente

**Rama:** `feature/trazabilidad-expediente` (desde `develop` en `fdcbccf`)
**Card:** Trello #144 · Sprint 9 · Fede
**Estado:** backend y frontend terminados, verificados en vivo contra Railway con el stack de Gemini.

---

## Qué pide la card

Una sola solapa del expediente donde el analista vea, junto: cada **regla dura evaluada** (con su
resultado, no solo las que bloquearon), el **estado de la póliza al momento de clasificar**, **todas
las pólizas** del asegurado en esa aseguradora, la **cantidad y el monto** de sus siniestros previos,
y sus **antecedentes de fraude**. Todo sale de lo ya persistido — nada se recalcula ni se pide en vivo.

---

## Lo hecho

### Backend (commit `3555855`)

| Punto | Archivo | Qué |
|---|---|---|
| 1 | `RuleResultRepository` | `findByCaseIdOrderByEvaluatedAtAsc` — la interfaz estaba vacía |
| 1 | `RuleResultResponse` (**common-lib**) | DTO nuevo |
| 1 | `ClassificationResultsService` | `getRuleResults(caseId)` |
| 1 | `ClaimController` | `GET /api/v1/claims/{caseId}/rule-results` |
| 2 | `CaseService` / `CaseServiceImpl` | `getInsuredPolicies(caseId)` |
| 2 | `CaseController` | `GET /api/v1/cases/{caseId}/insured-policies` |
| 3 | `CaseResponse` | + `ruleResults`, `policySnapshot`, `insuredPolicies` |
| 3 | `PolicySnapshotResponse` | DTO nuevo (cases-service) |
| 3 | `ClaimsAnalysisClient` / `ClassificationServiceClient` | `ruleResultsOf(caseId)` |

### El monto histórico, que no estaba persistido

La card pide "cantidad de siniestros previos **y monto total reclamado**". El count vivía en
`policy_snapshot.previous_claims`; el monto no vivía en ningún lado: classification-service lo
calcula en cada corrida (`InsuredHistory.totalAmountClaimed`, que suma
`siniestro_historico.monto_indemnizado`), lo mete en el prompt y lo tira.

- Columna nueva `policy_snapshot.total_amount_claimed`, **nullable** al revés que el count: un
  snapshot anterior a la columna no tiene manera de saber el monto, y un 0 se leería como "nunca
  reclamó un peso". La pantalla muestra "Sin datos" en esos.
- La escribe `PolicySnapshotRepository` (classification-service), donde ya se escribía el resto de
  la foto. La lee la entidad `PolicySnapshot` de cases-service.
- `db/init-multitenant.sql` actualizado + `db/migrations/2026-08-28-monto-historico-snapshot.sql`
  para las bases que ya existen. **Ya aplicada en Railway** (los dos esquemas).

### Bug encontrado al levantarlo: el backend de ayer no funcionaba contra una base real

`GET /cases/{id}` devolvía **500** — `LazyInitializationException` sobre
`PolicySnapshot`. `Case.policySnapshot` es `@ManyToOne(LAZY)` y `loadCase` mapea fuera de toda
transacción, así que el proxy nunca se podía inicializar. Los tests no lo agarran porque mockean
los repositorios.

Resuelto con `CaseRepository.findPolicySnapshot(caseId)` (query explícita) en vez de `getPolicySnapshot()`
sobre la entidad. No se pasó la relación a EAGER a propósito: se joinearía también en cada listado,
donde nadie la lee.

### Frontend — la solapa

`core/models/trazabilidad.ts` (nuevo) + la solapa "Trazabilidad" en `expediente-detail`, con el
mismo gateo por presencia de datos que "Razones"/"Peritaje". Tres secciones fijas y una condicional:

| Sección | Fuente |
|---|---|
| Reglas evaluadas | `CaseResponse.ruleResults` |
| La póliza al momento de clasificar | `CaseResponse.policySnapshot` |
| Otras pólizas del asegurado | `CaseResponse.insuredPolicies`, menos la de este siniestro |
| Antecedentes de fraude | `GET /cases/{id}/fraud-record/insured` (de Mar), **solo si hay** |

**`evaluated_value` se traduce a una frase.** El motor lo escribe como pares `clave=valor`
(`reportedAt=+20h max=72h`, `eventDate=... coverageWindow=...`): es el registro de auditoría y en la
base queda tal cual, pero en pantalla era una línea de log. `ruleEvaluationText()` lo convierte por
tipo de regla ("Denunciado 20 h después del hecho · máximo 72 h"). Mismos números, no se recalcula
nada. Un tipo de regla desconocido cae al literal crudo antes que esconderse.

**Los antecedentes quedan en modo lectura y solo si existen.** Registrar un antecedente es un acto
sobre la persona y su tarjeta sigue en el Resumen, con el botón. Repetir la tarjeta vacía en
Trazabilidad daba dos carteles diciendo lo mismo — y la card pide los antecedentes "si los hay".

### Tres decisiones del backend que conviene no deshacer

**El acceso a las pólizas sale del caso, no del DNI.** `getInsuredPolicies` corre `readableCase`
(que ya hace `accessPolicy.assertCanRead`) y recién ahí pide las pólizas. Por eso NO se tocó el
`@PreAuthorize` de `GET /policies?insuredId=`: sumarle el rol ahí le daría acceso a cualquier
asegurado por DNI.

**Las reglas degradan a lista vacía si classification-service no responde** — al revés que
`fraudRecordsOf`, que deja explotar el error a propósito (una lista vacía se leería como "no tiene
antecedentes"). Acá la trazabilidad es contexto: perderla no puede tumbar el detalle del expediente.

**Los tres bloques van agrupados en el record `Traceability`** dentro de `CaseServiceImpl`. Solo
`GET /cases/{id}` lo puebla; el resto usa `Traceability.none()`.

---

## Verificado en vivo (28/08)

Stack reconstruido de cero (`dev-gemini.ps1 --build`) contra Railway, ya con `develop` mergeado, con
el analista de BBVA.

**Expediente #38** (Martina Soteras, 6 reglas evaluadas, snapshot, 5 pólizas):

- Las 6 reglas se listan con las que **cumplen** incluidas, con su frase legible.
- La póliza al clasificar muestra suma asegurada, vigencia, mora y siniestros previos, con la fecha
  de la consulta.
- Las otras 4 pólizas del asegurado salen bien, sin la del siniestro.

**El monto nuevo, end to end.** Se reintentó la clasificación (botón del analista, Gemini) de dos
expedientes que estaban trabados en `CLASSIFICATION_FAILED` desde la época de Ollama, y los dos
escribieron el snapshot con la columna:

| Caso | Asegurado | `previous_claims` | `total_amount_claimed` |
|---|---|---|---|
| #37 | Martina Soteras | 0 | $ 0 |
| #22 | Julián Pérez | 3 | $ 1.350.000 |

El 0 del #37 es el valor real, no un vacío: contrastado contra `aseguradora_bbva.siniestro_historico`,
Martina no tiene siniestros previos. El #22 coincide exacto con los 3 del histórico de Julián.

**Nota sobre el barrido de recuperación:** los 6 expedientes en `CLASSIFICATION_FAILED` tienen
`classification_failure_reason` en NULL (fallaron antes de que la columna existiera), así que el
barrido nuevo — que filtra por `INFRASTRUCTURE` — no los reencola solo. Los dos reintentos fueron
manuales.

Tests con Testcontainers incluidos (`mvn verify -Pit`): **271 en cases + 221 en classification**, en
verde. `mvn test` a secas da menos porque develop separó los ITs bajo el tag `it`.

---

## Lo que queda abierto

**1. La suma asegurada de los Hurtos va a mostrar la de Robo.** `arbiter_*.policy` aplana las
coberturas a una sola (`coverage_id`, `sum_insured`) mientras la BD Aseguradora modela varias por
póliza — verificado: las 11 pólizas de BBVA tienen `coverage_id = 1`. Es anterior a esta historia y
**Aylén es la indicada** para decir si es simplificación deliberada o gap. Detalle en
`docs/handoff-modelos-y-rendimiento.md` §4ter A. Conviene avisarle antes de que el número quede a la
vista del analista.

**2. `GET /cases/{id}` le manda al asegurado datos pensados para el analista.** El endpoint es
compartido (`isAuthenticated()`), así que un asegurado que pide su propio expediente recibe
`riskBreakdown`, `forensicReport`, `documentAnalyses` y ahora también `ruleResults` y
`policySnapshot`. Son datos **de él**, no de terceros, y el recorte hoy lo hace el frontend
(el portal del asegurado tiene otra pantalla). No lo introdujo esta historia — es el patrón que ya
tenían los tres campos anteriores — pero da para una historia propia de redacción por rol.

**3. "Monto total reclamado" suma `monto_indemnizado`.** Es lo que ya hacía `InsuredHistory` y lo
que el prompt viene llamando "Monto total reclamado histórico" desde siempre; se mantuvo el nombre
por consistencia. Si el negocio distingue reclamado de indemnizado, hay que separarlos en la BD
Aseguradora primero.
