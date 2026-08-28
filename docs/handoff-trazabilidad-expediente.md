# Handoff — H #144 · Trazabilidad completa del expediente

**Rama:** `feature/trazabilidad-expediente` (desde `develop` en `fdcbccf`)
**Card:** Trello #144 · Sprint 9 · Fede
**Estado:** backend terminado (puntos 1–3), **falta el frontend** (punto 4)

---

## Qué pide la card

Una sola solapa del expediente donde el analista vea, junto: cada **regla dura evaluada** (con su
resultado, no solo las que bloquearon), el **estado de la póliza al momento de clasificar**, **todas
las pólizas** del asegurado en esa aseguradora y sus **antecedentes de fraude**. Todo sale de lo ya
persistido — nada se recalcula ni se pide en vivo.

---

## Lo hecho (compila; 73 tests verdes en cases + 15 en classification)

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

### Tres decisiones que conviene no deshacer

**El acceso a las pólizas sale del caso, no del DNI.** `getInsuredPolicies` corre `readableCase`
(que ya hace `accessPolicy.assertCanRead`) y recién ahí pide las pólizas. El analista llega a ellas
**a través de un expediente que ya puede leer**. Por eso NO se tocó el `@PreAuthorize` de
`GET /policies?insuredId=`: sumarle el rol ahí le daría acceso a cualquier asegurado por DNI.

**Las reglas degradan a lista vacía si classification-service no responde** — al revés que
`fraudRecordsOf`, que deja explotar el error a propósito (una lista vacía se leería como "no tiene
antecedentes"). Acá la trazabilidad es contexto: perderla no puede tumbar el detalle del expediente.

**Los tres bloques van agrupados en el record `Traceability`** dentro de `CaseServiceImpl`. Sin eso
el mapper `toResponse` pasaba de 6 a 9 parámetros y cada endpoint de listado tenía que pasar tres
vacíos. Solo `GET /cases/{id}` lo puebla (`traceabilityOf`); el resto usa `Traceability.none()`.

---

## Lo que falta — punto 4, el frontend

Una solapa nueva en `expediente-detail.component`. **Ya está todo el patrón**: el componente tiene
siete solapas y el gateo por presencia de datos se resuelve en una línea
(`expediente-detail.component.ts:372`):

```typescript
...(this.peritaje() ? [{ id: 'peritaje' as TabId, label: 'Peritaje' }] : []),
```

**Alcance acordado con Fede:** *una sola* solapa "Trazabilidad" con las cuatro secciones, no cuatro
pestañas. Es lo que pide la card ("en una sola solapa") y el componente ya tiene siete.

Las cuatro secciones y de dónde salen:

| Sección | Fuente |
|---|---|
| Reglas evaluadas | `CaseResponse.ruleResults` — ya viaja |
| Póliza al clasificar | `CaseResponse.policySnapshot` — ya viaja |
| Otras pólizas del asegurado | `CaseResponse.insuredPolicies` — ya viaja |
| Antecedentes de fraude | `GET /api/v1/cases/{caseId}/fraud-record/...` — **ya existía**, es de Mar |

> **Ese cuarto punto de la card ya estaba hecho.** Mar dejó el endpoint devolviendo
> `List<FraudRecordResponse>`, con los vencidos incluidos (`inForce=false`) y marcando cuáles
> puntúan (`scores`). El front solo lo consume; no hay que tocar su lógica.

Recordar el **design system** (CLAUDE.md): `app-card`, `app-badge` con `tone` para el semáforo
PASS/FAIL, tokens semánticos, clases `.t-*`. Nada de hex/px crudos.

---

## Dato que la solapa va a exponer, y no es un bug de esta historia

El `policySnapshot` muestra la **suma asegurada congelada al clasificar**. En los casos de Hurto eso
va a mostrar la suma de **Robo**, porque `arbiter_*.policy` aplana las coberturas a una sola
(`coverage_id`, `sum_insured`) mientras la BD Aseguradora modela varias por póliza — verificado: las
11 pólizas de BBVA tienen `coverage_id = 1`. Detalle completo en
`docs/handoff-modelos-y-rendimiento.md` §4ter A.

Es anterior a esta historia y **Aylén es la indicada** para decir si es simplificación deliberada o
gap. Conviene avisarle antes de que el número quede a la vista del analista.

---

## Contexto de la jornada (27/08)

- **Develop trae Gemini por Vertex**, mergeado por Mar (PRs #51–#53). `LlmClient` como interfaz con
  `OllamaClient` y `GeminiClient` detrás; `OllamaDocumentAnalyzer` → `DocumentAnalyzerImpl`. Se
  levanta con `.\scripts\dev-ollama.ps1` o `.\scripts\dev-gemini.ps1`. Setup en
  `docs/proveedor-de-modelo.md`.
- **Gemini medido contra Qwen**, mismo caso y mismos documentos: **1 min 31 s contra 2 h 17 min**
  (90×), mismo veredicto, y `fields` **sí se puebla** con el prompt v4 — la regresión que
  arreglamos con el v5 era específica de Qwen.
- **gcloud queda configurado** en la máquina de Fede con `mocciafederico@hotmail.com` (su Hotmail ya
  era cuenta de Google, así que el binding de IAM de Mar funcionó tal cual).
- **Ojo en PowerShell:** `gcloud` a secas rebota por la ExecutionPolicy — hay que usar
  **`gcloud.cmd`**. No está en el doc de Mar y le va a pasar al resto del equipo.
- La rama `feature/investigacion-modelos` quedó aparte con los prompts v4/v5 y el banco de pruebas,
  sin mergear. El merge a develop da **un solo conflicto de dos líneas** (`orderedMap` contra el
  cambio de `OllamaClient` a `LlmClient`).
