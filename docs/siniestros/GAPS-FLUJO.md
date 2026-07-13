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

## Gap A — `FALTA_DOCUMENTACION` cae en la bandeja del analista

**Diagrama:** cuando falta documentación, se le solicita **al asegurado** y el caso queda esperando
(rama "El asegurado adjunta la documentación", que hace loop de vuelta al OCR). El analista **no**
interviene en esta rama.

**Hoy:** [`ClassificationServiceClient.refreshClassification`](../../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/ClassificationServiceClient.java)
setea `PENDING_ANALYST_REVIEW` para **cualquier** clasificación, incluida `FALTA_DOCUMENTACION`.
En la BD seed, los cases 3 y 4 (`FALTA_DOCUMENTACION`) quedan en la cola del analista cuando
deberían estar esperando al asegurado. El enum `CaseStatus` ni siquiera tiene un valor para
representar "esperando documentación del asegurado".

**Qué falta:** un estado tipo `AWAITING_DOCUMENTATION` + la transición que lo distingue de
`PENDING_ANALYST_REVIEW`. **Diferido** hasta tener los diagramas de estado (no solo el de flujo).

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

## Gap B — La decisión del analista no existe *(lo toma Fede)*

**Diagrama:** todos los caminos terminan en *"se manda la resolución al analista, quien debe aprobar"*.

**Hoy:** no hay endpoint de decisión. La entidad `ClassificationLog` tiene los campos preparados
(`analystId`, `decision`, `decisionTimestamp`) pero ningún código los escribe. El flujo queda
trunco en `PENDING_ANALYST_REVIEW`. Además incumple la auditoría de la Disposición 2/2023, que
exige registrar la decisión del analista.

**Qué falta:** `POST /api/v1/claims/{id}/decision` (APROBAR | RECHAZAR), estados post-decisión en
`CaseStatus` (`APPROVED` / `REJECTED`), y persistir la decisión en el log. → **Asignado a Fede.**

---

## Gaps conocidos y aceptados (no bloqueantes)

- **Gap D — Análisis de fraude en el prompt.** El diagrama lista "análisis de fraude" como parte del
  contexto del LLM. Reglas e historial sí se inyectan (`PromptBuilder`); la detección de imagen
  reutilizada con pgvector **no** está. Ya está diferido explícitamente en la arquitectura
  ("no bloqueante para arrancar").
- **Gap E — Analista asignado.** El diagrama dice "al analista **asignado**". No hay usuarios, roles
  ni asignación porque dependen de `auth-service` (Auth0), que no está levantado. Mientras tanto el
  `analystId` del Gap B se puede recibir como campo del request.
