# Handoff: revisión de código pendiente

Revisión general hecha el 22/09/2026 sobre `fix/ui-pestanas-analisis`. La limpieza de documentación,
código muerto y comentarios se hizo el 23/09 en `chore/limpieza-codigo`. Este documento junta lo que
falta.

Actualizado el 05/10/2026 al cerrar `chore/front-ingles`: se sacaron los ítems ya resueltos (chat en
tiempo real, columna `described_claim_cause`, identificadores en castellano, lógica en controllers,
respuestas con `Map`, servicios `Internal*`, propiedades sin declarar, autor del historial de reglas,
literales crudos del historial, Reglas en tablet y la "e" del monto a pagar).

Este archivo se borra cuando se vacíe.

---

## 1. Temas en espera (necesitan charla, no código)

### 1.1 "17" contra "18" siniestros previos
No es un bug de cálculo: es el mismo dato contado de dos formas.
- La regla `MAX_EVENTS_YEAR` guarda `events12m`, que es el número de evento **contando este
  siniestro** (18). La pantalla lo muestra como "18 siniestros en los últimos 12 meses".
- El motivo de esa misma regla dice cuántos hubo **antes** (17).
- El score y el modelo usan sus propios conteos de "previos".

Falta decidir un solo criterio para todo lo que ve el analista. Código: `TemporalRuleEvaluator`
(classification-service) y `core/models/traceability.ts` (front).

### 1.2 PRs a `main` desde `hotfix/*`
`.github/workflows/main.yml` acepta `hotfix/*` y `guard-main.yml` no, así que un hotfix nunca pasa.
Hay que decidir si se aceptan hotfixes directos a `main` y dejar un solo workflow.

---

## 2. Restos del flujo de prueba aislado (classification-service)

Se borraron los endpoints de prueba, pero quedaron piezas que solo existían para ellos y que ya
cambian lógica al sacarlas:
- Los guards `caseId == null` en `CaseOutcomeRepository` y `ClassificationResultsService`, y la rama
  `caseDocumentId == null` en `ImageEmbeddingService`.
- Los overloads `ClassificationOrchestrator.classify(ClaimReport)` y `classify(ClaimReport, List)`:
  producción solo usa `classify(caseId, claim, docs)`, pero estos sostienen ~25 tests del orquestador
  (6 de `ClassificationOrchestratorIntegrationTest` usan el camino sin documentos). Sacarlos implica
  pasar esos tests al camino con documentos, que no es mecánico.

---

## 3. Convenciones del front que no se cumplen

- **Botones hechos a mano** que deberían ser del kit: los chips de filtros y "Limpiar todo" de la
  bandeja.
- **`.measure` sobre párrafos** (riesgo de texto cortado dentro de cards): quedan el aviso de
  cobertura en Reglas y el subtítulo del seguimiento del asegurado. El detalle del expediente ya lo
  resolvió poniendo el límite en la card y no en el texto.

---

## 4. Otros hallazgos (no urgentes)

- **Archivos sin referencias:** `docs/wireframes/Arbiter Wireframes.html`,
  `docs/siniestros/diagrama-flujo-clasificacion.pdf` y los PNG de `public/brand/` (se usan los SVG).
  Confirmar si se conservan.
- **Formulario "Identificate"** en Mis siniestros (y el aviso que lo nombra en el alta de denuncia):
  quedó de antes de Auth0; revisar si sigue haciendo falta.
- **Rendimiento en local:** no es de la app. Desde local cada consulta a la base de Railway tarda
  ~145 ms, así que los endpoints con muchas consultas (`GET /cases/{id}`, `GET
  /settlement-authorities`, guardar una decisión) se sienten lentos; deployado anda bien. Perfilar las
  N+1 solo si aparece en el deploy.
- **Alta de denuncia con fecha "Hoy" y una franja futura** (por ejemplo, Noche a las 20 h): el back
  la rechaza por hora futura (`@PastOrPresent`), pero la pantalla muestra "no se puede determinar si
  tu póliza permite nuevos siniestros". Debería decir que la hora no puede ser futura, o no ofrecer
  esas franjas.
- **Inicio del analista:** "Resumen de Arbiter · Próximamente" es un placeholder a la vista.
