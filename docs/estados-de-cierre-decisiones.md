# Estados de cierre — mapa y decisiones

Historia: "Como analista quiero cerrar un expediente por el motivo real que lo terminó". Rama
`feature/estados-de-cierre`, que sale de `feature/onboarding-aseguradora`.

Valentina pidió que el explore-unknowns y su validación los haga Claude por su cuenta, el 14/10/2026,
sin ella. **Todas las decisiones de este documento las tomó Claude.** Cada una dice por qué, para que
se pueda cambiar alguna sin rearmar el resto.

## Cuadrante 1 · Lo que ya sabíamos (leído en el código)

- `CaseStatus` tiene 9 valores; los terminales son `APPROVED`, `REJECTED` y `LAPSED`
  (`CaseStatusService.TERMINAL_STATUSES`).
- Las transiciones viven en `CaseStatusService.VALID_TRANSITIONS`, fijas en código (decidido el 26/08).
  Todo terminal vuelve solo a `PENDING_ANALYST_REVIEW`, por la reapertura (`POST /cases/{id}/reopen`).
- Todo lo que cuenta "cerrado" sale de `TERMINAL_STATUSES`: la Bandeja (`CaseSpecifications`, lentes),
  "Por vencer", la alerta de vencimiento (`DeadlineSweepScheduler` vía `isDeadlineRunning`) y la carga
  por analista. Sumar un estado ahí lo saca de todos esos lugares.
- `reports-service` usa `case_status.is_final` de la base, no el enum. Las métricas diarias guardan una
  fila por estado final (`metrics_daily_resolution.final_status`): un estado nuevo entra sin cambiar el
  esquema.
- La prescripción la decide el motor (`ClassificationOrchestrator.isPrescribed`, art. 58): devuelve
  `LLM_NO_RECOMIENDA_APROBAR` con un `RuleFinding` de tipo `PRESCRIPTION`. El analista rechaza y el
  expediente queda en `REJECTED`. El dato de la prescripción queda solo en los factores.
- El asegurado ve un estado simplificado: `case_status.insured_status` (texto) y
  `insured_status_code` (`InsuredStatus`), desde la migración del 11/10 de `develop`.
- La decisión del analista queda en la auditoría inmutable de `classification-service`
  (`case_classification.decision`, `VARCHAR(20)`), que hoy recibe `APPROVE` o `REJECT`.
- `CaseNotificationService` manda un mail por estado que le importa al asegurado.

## Cuadrante 2 · Preguntas que se podían nombrar, y su decisión

| # | Pregunta | Decisión | Por qué |
|---|---|---|---|
| D1 | Nombres de los estados | `WITHDRAWN` (Desistido), `VOIDED` (Baja por error), `PRESCRIBED` (Prescripto). Ids 10, 11 y 12 en `case_status` | En inglés, como pide CLAUDE.md. Ids nuevos al final: no se renumera nada |
| D2 | ¿Desde qué estados se puede cerrar como desistido o baja por error? | Desde `PENDING_ANALYST_REVIEW`, `AWAITING_DOCUMENTATION` y `CLASSIFICATION_FAILED` | Son los estados donde el expediente espera a la aseguradora o al asegurado. Desde `PENDING_CLASSIFICATION` no: la clasificación corre en paralelo y su aviso chocaría con el cierre. Desde peritaje o reparación tampoco: la derivación abierta respondería sobre un expediente cerrado |
| D3 | ¿Quién cierra? | El analista asignado, igual que para decidir | La historia es del analista. Mismo criterio que la decisión: asignarse primero, así un cierre nunca asigna de forma implícita |
| D4 | ¿Cómo se llega a Prescripto? | En el modal de Rechazar, el analista elige "Rechazo por: el fondo / prescripción". Si el motor marcó prescripción, viene elegida | Human-in-the-loop: el analista decide siempre (decisión #5). Que venga elegida evita el error de olvidarlo; que se pueda elegir cubre una prescripción que el motor no detectó |
| D5 | ¿Qué registra la auditoría? | Prescripto: `REJECT` (es un rechazo, por motivo legal). Desistido: `WITHDRAW`. Baja por error: `VOID`. Si el expediente no tiene clasificación (por ejemplo, `CLASSIFICATION_FAILED`), el cierre no va a la auditoría | La auditoría registra lo que el analista hizo con cada clasificación (Disposición 2/2023). Un cierre sin clasificación no tiene nada que auditar; el historial del expediente sí lo registra |
| D6 | ¿Dónde queda el motivo obligatorio? | En la fila del historial: `reason` = "expediente cerrado: desistido" o "…: baja por error"; `observation` = lo que escribió el analista | Ya es el lugar del texto de la persona (como "devolver liquidación") y se muestra en la línea de tiempo |
| D7 | Estado del asegurado | Desistido y Baja por error → "Cerrado" (código nuevo `CLOSED`). Prescripto → "Rechazado" (`REJECTED`) | La historia pide "sin motivos internos". Para el asegurado, la prescripción es un rechazo |
| D8 | Mail al asegurado | Prescripto: el mismo de Rechazado. Desistido y Baja por error: uno nuevo, "Cerramos tu denuncia" | Mismo criterio que D7 |
| D9 | Tono en el front | Prescripto `danger`, como Rechazado. Desistido y Baja por error `neutral` | El semáforo es para estado: un cierre administrativo no es ni bueno ni malo |
| D10 | Métricas del referente | `MetricsSummary` suma `prescribedCases`, `withdrawnCases` y `voidedCases`. "Decididos" = aprobados + rechazados + prescriptos; la tasa de rechazo queda sobre los rechazados por el fondo | Lo pide la historia: "cuántos se rechazan y por qué". Desistidos y bajas no los decidió nadie, como los caducados |
| D11 | Migración | `db/migrations/2026-10-14-estados-de-cierre.sql`, con `2026-08-31-caducidad.sql` como molde. Idempotente. Se aplica en Railway (aditiva, permiso del 14/10) | La pide la historia |
| D12 | ¿Reabrir? | Los tres estados nuevos vuelven a `PENDING_ANALYST_REVIEW` por la reapertura que ya existe | Lo pide la historia (punto 11 del doc de gaps) |
| D13 | ¿Se aplica la migración en Railway ahora? | **No.** Queda lista y probada; se aplica al mergear | El código de `develop` lee todas las filas de `case_status` como enum (`insuredStatuses()`): una fila con `CLOSED` le haría responder 500 al equipo y a lo desplegado hasta que entre este PR. Es aditiva pero no compatible hacia atrás |

## Cuadrante 3 · Lo implícito

- Textos de la UI con las reglas de redacción de Valentina: acciones, plurales con artículo, sin
  "lo que + verbo + sujeto", sin repetir raíz. Catálogo en la sección "Textos".
- Nada de cambiar las transiciones a base de datos: fijas, como se decidió el 26/08.
- Fuera de alcance, como dice la historia: mediación/juicio, autorizado a liquidar, liquidado y
  denuncia de hecho.

## Cuadrante 4 · Riesgos encontrados

| # | Riesgo | Cómo se cubre |
|---|---|---|
| R1 | Un `switch` sobre `CaseStatus` sin `default` deja de compilar o, peor, cae en un caso equivocado | Revisar cada `switch` del enum en los cinco módulos y en el front (`Record<CaseStatus, …>` obliga a completar) |
| R2 | `reports-service` exporta PDF y CSV con columnas aprobado/rechazado | Los estados nuevos van con su etiqueta; la columna de decisión suma `WITHDRAW` y `VOID` |
| R3 | El prompt del LLM describe el historial del asegurado por estado (`PromptBuilder`) | Sumar las tres descripciones; si falta una, el prompt mostraría el nombre interno |
| R4 | La validación en el navegador manda mails reales al asegurado de La Segunda | Validar con `cases-service` sin clave de SendGrid |

## Textos

| Dónde | Texto |
|---|---|
| Etiquetas de estado | Prescripto · Desistido · Baja por error |
| Detalle, botón | Cerrar expediente |
| Detalle, opciones del botón | El asegurado desistió · Baja por error de carga |
| Modal de cierre, título | ¿Cerrar el expediente por desistimiento? · ¿Dar de baja el expediente por error? |
| Modal de cierre | Motivo * · Por qué se cierra… · El asegurado ve el expediente como Cerrado. |
| Modal de cierre, botón | Cerrar expediente · Dar de baja |
| Modal de rechazo | Rechazo por: El fondo del siniestro · Prescripción |
| Tarjeta de resolución | Rechazado por prescripción el {fecha} · Cerrado por desistimiento · Dado de baja por error de carga |
| Historial | expediente cerrado: el asegurado desistió · expediente cerrado: baja por error de carga · decisión del analista: Rechazado por prescripción |
| Error | No se pudo cerrar el expediente. |
| Asegurado (estado) | Cerrado (título "Cerramos tu denuncia", movimiento "Denuncia cerrada") |
| Asegurado (texto) | Cerramos la denuncia de tu siniestro. Te enviamos el detalle por correo electrónico. |
| Asegurado (próximo paso) | No quedan pasos pendientes. Si querés continuar con el reclamo, comunicate con nosotros. |
| Mail Desistido / Baja | Asunto "Cerramos tu denuncia". "Cerramos la denuncia de tu siniestro. Si querés continuar con el reclamo, comunicate con nosotros." |
| Mail Prescripto | El mismo de Rechazado |
| Tablero, regla que frena | Prescripción (art. 58) · La denuncia se presentó más de un año después del hecho. |

## Registro de decisiones tomadas al implementar

| # | Decisión | Por qué |
|---|---|---|
| I1 | La lista de terminales y la de "decididos" pasan a `common-lib`: `CaseStatus.terminal()` y `CaseStatus.decided()` | Las usaban `cases-service` y `reports-service` por separado, y `PdfFraudReportExporter` tenía su propia copia. Mismo criterio que `pausingTheTerm()` |
| I2 | Las consultas de métricas usan `:decided` en vez de `:approved, :rejected` (tiempo de resolución, plazo legal, fraude, Fast Track) | Para que los prescriptos cuenten como decididos. "Acuerdo con la recomendación" no cambia: la prescripción la clasifican las reglas y ya quedaba afuera |
| I3 | El ahorro por fraude sigue contando solo `REJECTED` | Un prescripto no se frenó por el fraude |
| I4 | El inicio del referente cuenta los resueltos con `scope=CLOSED` en lugar de sumar aprobados, rechazados y caducados | Toma cualquier terminal nuevo sin tocarlo, y son dos pedidos menos |
| I5 | `PromptBuilder` describe los estados nuevos, y también `PENDING_REPAIR`, que faltaba | Sin descripción, el prompt mostraba el nombre interno |
| I6 | Al tablero le faltaba la etiqueta de la regla `PRESCRIPTION` | Aparecía el literal en "Reglas que más frenan" |
| I7 | `GET /cases/{id}/decision` devuelve el veredicto solo de Aprobado, Rechazado y Prescripto | El motivo de un cierre está en el historial y la tarjeta lo lee de ahí |
| I8 | El 422/409 de cerrar desde un estado no permitido se valida antes de llamar a la auditoría (`assertCanMove`) | Un cierre rechazado nunca deja una decisión auditada |

## Validación (14/10/2026)

Contra un Postgres local con `init-multitenant.sql` y `seed-demo.sql` (no Railway, por D13), con
`cases-service` sin clave de SendGrid: no salió ningún mail.

| # | Qué | Resultado |
|---|---|---|
| E1 | API: desistido y baja por error desde revisión, falta documentación y clasificación fallida; sin asignar 409; sin motivo 400; `PRESCRIBED` como cierre 400; referente 403; decidir o cerrar un cerrado 409; prescribir con monto 400; prescribir 200; reabrir 200 | OK |
| E2 | Auditoría: `WITHDRAW` y `VOID` con el motivo; prescripto como `REJECT`; sin fila para un caso sin clasificación | OK |
| E3 | Detalle del analista: "Cerrar expediente" con sus dos opciones, modal con motivo obligatorio, tarjeta con el motivo; en falta documentación aparece al asignarse | OK |
| E4 | Rechazo de un caso con prescripción marcada: viene elegida "Prescripción" | OK |
| E5 | Bandeja: los tres estados en Cerrados y nunca en Abiertos ni en "Por vencer"; lentes | OK |
| E6 | Historial con el motivo | OK |
| E7 | Métricas: rechazados, prescriptos, desistidos y bajas por separado; tasas sobre decididos; tablero con la distribución por estado | OK, después de corregir H1 |
| E8 | Asegurado: "Cerrado" en la lista y el detalle, sin el motivo; teléfono sin desplazamiento horizontal | OK |
| E9 | Tests: `cases` (estados, cierres, prescripción), `reports` (etiquetas, resumen, integración con Postgres), front (modelos); suites completas, lint, formato y build | OK |

| # | Hallazgo | Estado |
|---|---|---|
| H1 | `fraudDetection` seguía usando `:rejected` después de pasar a `:decided`: `GET /reports/metrics` respondía 500 | Corregido, con un test de integración que lo cubre |
| H2 | "Reglas que más frenan" mostraba `PRESCRIPTION` sin traducir | Corregido (I6) |

## Para mergear

1. Aplicar `db/migrations/2026-10-14-estados-de-cierre.sql` en Railway **junto con el deploy** de este
   código, no antes (D13).
