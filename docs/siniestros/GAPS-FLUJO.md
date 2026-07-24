# Gaps del flujo — Módulo de Análisis y Clasificación

> Contraste entre el flujo aprobado ([`diagrama-flujo-clasificacion.pdf`](diagrama-flujo-clasificacion.pdf))
> y lo que hoy está implementado en `classification-service` + `cases-service`.

El diagrama define este recorrido:

```
crear expediente (info estructurada + adjuntos)
  → OCR de la documentación
  → validación determinística Fast Track (reglas de la aseguradora)
  → ¿Falta documentación adicional?
       Sí → se solicita al asegurado → [el asegurado adjunta] → vuelve al OCR
       No → ¿Es Fast Track?
              Sí → se manda la resolución al analista, quien debe aprobar
              No → análisis del LLM (prompt con reglas, análisis de fraude, historial)
                     → ¿recomienda aprobar?
                          Sí/No → se manda la resolución al analista, quien debe aprobar
                          revisión manual total → el analista revisa sin asistencia del LLM
```

**Lo que sí se cumple:** el tramo *denuncia → OCR → Fast Track determinístico → clasificación del LLM
→ bandeja del analista* está implementado y andando (ver `ClassificationOrchestrator.classify`).

Lo que sigue son los desvíos.

---

## Gap A — Estado para documentación faltante ✅ Resuelto (backend)

**Diagrama:** cuando falta documentación, se le solicita **al asegurado** y el caso queda esperando
(rama "El asegurado adjunta la documentación", que hace loop de vuelta al OCR). El analista **no**
interviene en esta rama.

**Antes:** `refreshClassification` seteaba `PENDING_ANALYST_REVIEW` para cualquier clasificación,
incluida `FALTA_DOCUMENTACION` — los casos con documentación faltante caían en la cola del analista.

**Ahora:** [`ClassificationServiceClient.statusFor`](../../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/ClassificationServiceClient.java)
rutea por clasificación: `FALTA_DOCUMENTACION` → **`AWAITING_DOCUMENTATION`** (espera al asegurado);
el resto → `PENDING_ANALYST_REVIEW`. El loop de vuelta ya existía: `POST /cases/{id}/documents`
resetea a `PENDING_CLASSIFICATION` y re-clasifica con el set completo de adjuntos (Gap C).
El scheduler solo pollea `PENDING_CLASSIFICATION`, así que un caso esperando documentación no se
re-procesa hasta que el asegurado actúe.

Se agregaron también `APPROVED` / `REJECTED` a `CaseStatus` — son los estados finales que setea el
endpoint de decisión (Gap B, Fede): `PENDING_ANALYST_REVIEW → APPROVED | REJECTED`.

### Estados internos vs. lo que ve el asegurado

Los `CaseStatus` internos quedan granulares (en inglés); el asegurado ve una **proyección**, según
los diagramas de estado aportados (denuncia y expediente). El mapeo es responsabilidad del frontend
(pendiente de implementar ahí):

| Interno (`CaseStatus`) | Denuncia (asegurado) | Expediente (asegurado) | ¿Analista actúa? |
|---|---|---|---|
| `PENDING_CLASSIFICATION` | Pendiente de clasificación | Recibido | No |
| `PENDING_ANALYST_REVIEW` | Pendiente de clasificación | Recibido | **Sí** |
| `CLASSIFICATION_FAILED` | Pendiente de clasificación | Recibido | A definir con analistas |
| `AWAITING_DOCUMENTATION` | Esperando documentación adicional | Recibido (acción pendiente) | No |
| `APPROVED` *(Gap B)* | Aprobado | Aprobado | No (final) |
| `REJECTED` *(Gap B)* | Rechazado | Rechazado | No (final) |

> El POV del analista (bandeja, asignación, prioridades) queda para discutir con los analistas.
> El invariante que ya se cumple: puede saber si tiene que actuar mirando solo el estado.

---

## Gap C — Persistencia de adjuntos ✅ Resuelto

**Diagrama:** el expediente **acumula** documentación. El asegurado sube lo que falta y la
validación se re-corre sobre **todo** lo que el expediente tiene.

**Antes:** los archivos viajaban por multipart hasta el LLM y se descartaban. No había tabla de
adjuntos, así que `addDocumentsAndReclassify` re-clasificaba pasando **solo lo que vino en ese
request** → en el 3er paso volvía a pedir un documento ya entregado. El loop del diagrama solo
funcionaba si el asegurado subía todo junto en un único request.

**Ahora:** los adjuntos se persisten en `cases-service` y cada re-clasificación manda el set
**completo** acumulado.

- Entidad [`CaseDocument`](../../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/models/entities/CaseDocument.java)
  (tabla `case_documents`): un registro por `(case_id, type)` con los bytes en `bytea`. Re-subir un
  tipo lo reemplaza (upsert por tipo, garantizado por unique constraint).
- [`CaseServiceImpl.storeDocuments`](../../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/CaseServiceImpl.java)
  persiste cada documento al crear el caso y al re-subir; después manda a clasificar
  `caseDocumentRepository.findByCaseId(...)` — el set completo, no solo lo nuevo.
- [`ClassificationServiceClient`](../../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/ClassificationServiceClient.java)
  arma el multipart desde los bytes persistidos (el contrato pasó de `Map<String, MultipartFile>` a
  `List<CaseDocument>`).

La secuencia del diagrama (subir `police_report`, después `item_photo` en requests separados) ya no
vuelve a pedir lo que el asegurado ya mandó.

> **Pendiente relacionado:** los bytes se guardan en la BD (`bytea`). La arquitectura prevé S3 para
> adjuntos — migración diferida, no bloqueante.

---

## Gap B — Decisión del analista ✅ Resuelto

**Diagrama:** todos los caminos terminan en *"se manda la resolución al analista, quien debe aprobar"*.

**Antes:** no había endpoint de decisión. La entidad `ClassificationLog` tenía los campos preparados
(`analystId`, `decision`, `decisionTimestamp`) pero ningún código los escribía. El flujo quedaba
trunco en `PENDING_ANALYST_REVIEW`, incumpliendo la auditoría de la Disposición 2/2023.

**Ahora:** el flujo de decisión atraviesa los dos módulos:

1. **`cases-service`:** [`CaseController.recordDecision`](../../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/controllers/CaseController.java)
   recibe `POST /api/v1/cases/{caseId}/decision` con `{ analystId, decision }`.
   [`CaseServiceImpl.recordAnalystDecision`](../../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/CaseServiceImpl.java)
   forwarda la decisión a classification-service y transiciona el caso a `APPROVED` o `REJECTED`
   vía el choke point (`CaseStatusService`), dejando trazabilidad completa.

2. **`classification-service`:** [`ClaimController.recordDecision`](../../classification-service/src/main/java/ar/edu/utn/frba/arbiter/classification/controllers/ClaimController.java)
   recibe `POST /api/v1/claims/{caseId}/decision`.
   [`ClassificationResultsService.recordAnalystDecision`](../../classification-service/src/main/java/ar/edu/utn/frba/arbiter/classification/services/ClassificationResultsService.java)
   persiste `analystId`, `decision` (normalizado: APROBAR→APPROVE, RECHAZAR→REJECT) y
   `decisionTimestamp` en el `ClassificationLog` — registro inmutable de auditoría (Disposición 2/2023).

**Auditoría:** la decisión queda registrada en dos niveles complementarios:
- `classification_log` (classification-service): quién decidió, qué decidió, cuándo.
- `case_status_history` (cases-service): transición `PENDING_ANALYST_REVIEW → APPROVED | REJECTED`, actor `ANALYST`.

**Frontend:** Fede agregó los botones de aprobar/rechazar en el detalle del expediente
(`expediente-detail.component`), que llaman a `POST /api/v1/cases/{id}/decision`.

---

## Gaps conocidos y aceptados (no bloqueantes)

- ~~**Gap D — Análisis de fraude en el prompt.**~~ **Resuelto.** El diagrama lista "análisis de
  fraude" como parte del contexto. Reglas e historial se inyectan en el prompt (`PromptBuilder`); la
  detección de imágenes se implementó como una **cascada** aparte (`ImageFraudAnalysisService`, corre
  en el orchestrator en el camino no-Fast-Track): primero CLIP + pgvector contra la base (imagen
  reutilizada de otro siniestro), y si no matchea, Google Vision Web Detection (imagen publicada en
  internet, opt-in). El resultado va al `RiskContext` y al reporte forense del `ClaimResponse`. Ver
  `docs/image-fraud-scoring-integration.md`. Lo único que queda abierto es activar los dos factores
  de imagen en el score → **Gap H**.
- **Gap E — Analista asignado.** El diagrama dice "al analista **asignado**". No hay usuarios, roles
  ni asignación porque dependen de `auth-service` (Auth0), que no está levantado. Mientras tanto el
  `analystId` del Gap B se puede recibir como campo del request.
- **Gap F — Filtro por aseguradora en la búsqueda de expedientes.** La historia "Búsqueda y filtrado
  de expedientes" (Sprint 6) pide filtrar `GET /api/v1/cases` por aseguradora según el rol del
  usuario autenticado. No implementado: depende de dos piezas que no existen todavía — `auth-service`
  (Auth0/JWT, mismo gap que E) y el esquema multi-tenant por aseguradora (decisión de arquitectura
  #10, `PostgreSQL` con schema separado por aseguradora). Filtrar "por aseguradora" no es un `WHERE`
  más sobre una columna: es resolver a qué schema de Postgres apuntar antes de correr la query. Sin
  el JWT no hay tenant que resolver. `GET /api/v1/cases` sí quedó extendido con el resto de los
  filtros de la historia (`status`, `claimCause`, `policyNumber`, `insuredId`, rango de
  `eventDate`) más paginación (`CaseServiceImpl.listCases`, `CaseSpecifications`).
- ~~**Gap G — Filtro por nivel de alerta de fraude en la búsqueda de expedientes.**~~ **Resuelto.**
  El HU oficial de H0011 ("Búsqueda y filtrado de expedientes") lista "nivel de alerta de fraude"
  como criterio de búsqueda; la card de Trello que se usó para scopear el Sprint 6 no lo incluyó, y
  quedó afuera de la primera iteración. Retomado a pedido explícito del equipo: `GET /api/v1/cases`
  ahora acepta `riskBand` (match exacto sobre `LOW`/`MEDIUM`/`HIGH`/`CRITICAL`), cableado en
  `CaseSpecifications#riskBand` + `CaseServiceImpl#listCases` + `CaseController`, mismo patrón que
  el resto de los filtros. Cubierto por `CaseRepositorySpecificationTests` (Postgres real) y
  `CaseControllerTest`/`CaseServiceImplTest` (mocks). El frontend lo expone como un `app-select` más
  en la bandeja del analista.
- **Gap H — Activación de los factores de imagen en el motor de scoring.** Los evaluators
  `ImageReuseEvaluator` e `ImageWebMatchEvaluator` están implementados, wireados y probados, pero
  **fuera del set activo** de la `ScoringConfig` del mock (igual que `PURCHASE_TO_REPORT_TIME` y
  `DOCUMENT_INCONSISTENCY`). Motivo: ambos son *no evaluables* (aportan 0) en los siniestros Fast
  Track y en los que no tienen imágenes; como el motor divide por el peso total, activarlos
  globalmente bajaría el score de todos esos casos y descalibraría las bandas H0012 (0.30 / 0.60 /
  0.80). El reporte forense igual llega al analista (vía `factors` + `forensicReport` del
  `ClaimResponse`); lo que falta es que **pondere** el número. Activarlos requiere una de dos cosas,
  a decidir con el equipo:
  1. **Recalibrar** — definir los pesos de los dos factores y reajustar los cortes de banda.
  2. **Fix del motor** — que `RiskScoringService` excluya del promedio ponderado los factores no
     evaluables (en vez de contarlos como 0). Esto además destrabaría los dos factores dormidos de
     develop. Requiere un flag "no evaluable" en `RiskFactorEvaluator.Contribution`.
  Ver `docs/image-fraud-scoring-integration.md` y el comentario en `MockRulesAdapter`.
