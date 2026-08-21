# Handoff — Antecedente de fraude (trazabilidad entre denuncias)

Continuación del trabajo de **derivación a peritaje**, que ya está terminado y mergeado en
`feature/fraude-derivacion-perito`. Este documento existe para retomar el segundo flujo desde cero
en otra conversación, sin tener que reconstruir el contexto.

Fecha: 2026-08-17.

> **Estado (2026-08-17): el backend está implementado.** Ver "Lo que quedó construido" al final.
> Falta el frontend (acción de confirmar en el detalle + alerta del antecedente + sección del
> referente para la ventana). Lo de acá arriba se deja como está porque es el porqué del diseño.

---

## El problema, en una línea

Arbiter tiene memoria **del expediente** pero no **de la persona**. Si un asegurado defraudó y se lo
comprobó, la próxima denuncia arranca como si nada hubiera pasado.

Evidencia concreta de que el ciclo está abierto:

- `ClaimFrequencyEvaluator` (classification-service) solo cuenta `previousClaimsCount`: **ignora el
  desenlace**. Tres siniestros pagados sin objeción puntúan igual que tres rechazados por fraude.
- `InsuredHistory` viene de la **BD Aseguradora** y el `InsurerAdapter` solo tiene `getPolicy` y
  `getHistory` — es de solo lectura, no hay camino de escritura. Nada de lo que Arbiter concluye
  vuelve a ningún lado.
- `Case.fraudDetermined` existe en el DER, con javadoc que dice *"final human determination that the
  claim was fraudulent — nothing writes this yet"*. **Es el ancla de este flujo.**
- El `ClassificationLog` es auditoría por expediente, no por persona.

Lo único que hoy cruza denuncias es la detección de **imágenes reutilizadas** con pgvector. Es el
precedente conceptual, pero sobre archivos y no sobre personas.

---

## Lo que ya está construido y sirve de base

La derivación a peritaje (ver el commit `feat(peritaje)`) dejó lo que faltaba para que exista un
fraude *comprobado* del que partir:

- `common.enums.ExpertVerdict`: `FRAUD_CONFIRMED` / `FRAUD_DISCARDED` / `INCONCLUSIVE`.
- Tabla `<tenant>.expert_assessment`: quién peritó, por qué se derivó, cuándo volvió el informe, el
  veredicto y el documento respaldatorio. Una por expediente.
- `CaseStatus.PENDING_EXPERT_REPORT` y las transiciones en `CaseStatusService`.
- `ExpertAssessmentService` con `derive(...)` y `receiveReport(...)`.
- Catálogo de peritos administrable por el referente (`expert_firm` + `ExpertFirmController` +
  la sección "Peritos" en la pantalla de reglas).
- Umbral de derivación como regla del motor: `insurer_rule` con `rule_type = 'EXPERT_DERIVATION'`.

**Un peritaje con `FRAUD_CONFIRMED` es hoy el único hecho verificado de fraude que el sistema
produce.** El antecedente se construye sobre eso.

---

## Decisiones ya cerradas con la usuaria

No hace falta volver a discutirlas.

1. **Lo confirma el analista, no el sistema.** Subir el informe del perito y registrar el antecedente
   son dos actos distintos: el perito verifica un hecho, el analista decide que eso constituye
   antecedente. El segundo es el que pesa sobre una persona.
2. **Tiene una ventana de vigencia configurable.** Un fraude de hace seis años no puede pesar igual
   que uno del año pasado. La ventana es configuración de la aseguradora.
3. **Llega al motor de reglas**, no solo al scoring.
4. **Dos orígenes, con peso distinto** — esto salió de que la usuaria quiere además una especie de
   lista negra para casos sin peritaje:
   - **Con respaldo pericial** (hay un `expert_assessment` con `FRAUD_CONFIRMED`): puntúa en el
     scoring y puede vetar Fast Track.
   - **Declarado por el analista sin peritaje** (confesión, evidencia obvia): se muestra como alerta
     al que revise la próxima denuncia, pero **no** puntúa.

   El motivo de la distinción: si la sospecha sin evidencia mueve el score, se arma un lazo que se
   retroalimenta solo — el score alto de hoy sube el score de mañana y no hay forma de salir.
5. **Vive en Arbiter, no en la BD Aseguradora.** Conceptualmente el antecedente es conocimiento de
   la compañía, pero escribir en esa base invierte la dirección de la integración, que hoy es de
   solo lectura con snapshots locales. El scoring lee las dos fuentes: el historial de la
   aseguradora más los antecedentes propios.
6. **No entra al prompt del LLM.** Misma razón por la que el score tampoco entra (decisión (A) de
   H0012): decirle al modelo "esta persona defraudó antes" contamina toda la lectura interpretativa
   y ya no se sabe si la recomendación sale de la denuncia o del prejuicio. Que pese en el scoring,
   que es explícito, ponderado y auditable.

---

## Cómo encaja con lo que ya existe

La arquitectura del scoring está preparada para recibirlo: es **una clase nueva**, no un refactor.

- Un `FraudHistoryEvaluator implements RiskFactorEvaluator` en
  `classification-service/services/risk/evaluators/`, más un peso en el `ScoringConfig` de la
  aseguradora (`FactorWeight` en rules-service). Los evaluadores existentes son el molde.
- Para el veto de Fast Track ya existe la columna: `InsurerRule.blocksFastTrack`. Esto además
  destraba el "veto de Fast Track por banda alta" que venía anotado como pendiente desde H0012.
- El antecedente vigente debería poder mirarse desde el detalle del expediente del analista, al lado
  del peritaje.

---

## Preguntas que estaban abiertas, y cómo se cerraron

1. **¿Puede haber antecedente sin peritaje?** Sí. El origen `ANALYST_DECLARED` existe y se distingue
   del pericial de tres formas: exige un motivo escrito de **20 caracteres mínimo** (el texto que un
   colega va a leer dentro de años al lado de una marca sobre una persona), no referencia ningún
   peritaje (constraint de BD), y la respuesta trae `scores=false` para que la pantalla pueda decir
   por qué esa alerta no mueve el gauge en vez de dejarlo implícito.
2. **¿La ventana es global de la aseguradora o por ramo?** Global. Una sola fila `insurer_rule`
   `FRAUD_RECORD` sin ramo ni cobertura: el antecedente es de la persona, no de la cobertura que
   afectó. Si mañana hace falta por ramo, se desdobla sin migrar datos.
3. **¿Dónde se registra?** Tabla propia, `<tenant>.insured_fraud_record`, en
   **classification-service**. Es el módulo que ya guarda memoria entre denuncias (los embeddings de
   pgvector) y el que la lee en el camino caliente del scoring; ponerla en cases-service habría
   agregado un hop REST por cada denuncia scoreada y un ciclo en el grafo de módulos. `Case
   .fraudDetermined` **también** se escribe ahora, desde cases-service.
4. **¿Qué pasa cuando el peritaje descarta el fraude?** No se registra antecedente. El dato ya está
   en el `expert_assessment` del expediente; meter un "no-antecedente" en la tabla que después
   alimenta scoring y motor de reglas mezcla dos cosas distintas. Si se quiere que se vea en la
   próxima denuncia, sale de mostrar el historial de peritajes del asegurado.
5. **¿El asegurado ve algo de esto?** No. Los endpoints son `ANALISTA_SINIESTROS` /
   `REFERENTE_ASEGURADORA`, igual que el peritaje.
6. **Ley 25.326 (datos personales).** Es el porqué de tres decisiones que quedaron en el código y
   conviene defender así: el antecedente **vence** (ventana configurable, y la vigencia se calcula,
   no se guarda — un flag guardado necesitaría un job y quedaría mal el día que el referente cambia
   la ventana); solo el **respaldo pericial** puntúa; y el registro **no se edita nunca**, para que
   qué dice y quién lo dijo sigan siendo legibles después.

---

## Pendientes que quedaron del peritaje (no bloquean, pero conviene cerrarlos)

- ~~**Contradicción front/back sobre el referente**~~ — **resuelto (19/8)** para decisión, asignación
  y reintento. El criterio que se eligió: el referente **supervisa** (asigna, reasigna, destraba una
  clasificación fallida) pero **no decide**. `decision` quedó en `hasRole('ANALISTA_SINIESTROS')` en
  cases-service **y en classification-service**, que tenía su propio `POST /claims/{id}/decision` con
  ambos roles — una vía para registrar una decisión salteándose el único módulo que resuelve quién
  decide contra `claims_analyst`, y además atribuyéndola a cualquier `analystId` del body.
  **Sigue abierto:** los dos endpoints de peritaje (`POST /expert-assessments` y `/report`) todavía
  admiten al referente en el backend mientras el front se los esconde.
- ~~**Copy de la card de decisión para el referente**~~ — **resuelto (19/8)**: ahora dice quién tiene
  la decisión y qué puede hacer él ("Espera la decisión de {analista}. Podés reasignarlo si hace
  falta").
- ~~**Sugerir la derivación desde el scoring**~~ — **resuelto (19/8)**: con banda `HIGH`/`CRITICAL` y
  derivación elegible, el botón pasa a variante `accent` con una nota en `--status-risk`. Se exige
  también que esté habilitada: sugerir un botón deshabilitado por monto mínimo sería peor que no
  sugerir nada.
- **Reintentos de clasificación**: se vieron expedientes con `"clasificación fallida tras 501
  reintentos"` y transiciones triplicadas en el historial. Es data anterior al fix de estabilización
  que trajo develop, pero si reaparece en expedientes nuevos hay que mirarlo.

---

## Lo que quedó construido (backend completo)

**common-lib** — `FraudRecordSource` (`EXPERT_BACKED` / `ANALYST_DECLARED`), `RuleType.FRAUD_RECORD`
(+ `RuleType.insurerWide()`, que es la consulta del motor; `insurerScoped()` sigue siendo el par que
edita el panel de reglas duras), y los DTOs `FraudRecordRequest` / `FraudRecordResponse`.

**classification-service** — dueño del antecedente.
- `InsuredFraudRecord` + repository + `InsuredFraudRecordService`.
- `POST /api/v1/fraud-records` y `GET /api/v1/fraud-records/insured/{dni}` (internos).
- `FraudHistoryEvaluator` (factor `fraud_history`): 1.0 si hay antecedente pericial vigente, 0.0 si
  no, **no evaluable** si la aseguradora no configuró la política. Varios antecedentes no acumulan.
- `FraudRecordRuleEvaluator`: el veto de Fast Track, con su `rule_result` PASS/FAIL.
- `BusinessRules.FraudRecordPolicy` + `RulesAdapter.getFraudRecordPolicy()`. El overlay **propaga**
  el error si rules-service no responde (a diferencia del de scoring): puede vetar Fast Track, y
  tragarse la caída expeditaría un siniestro que la aseguradora decidió no expeditar.

**rules-service** — `FraudRecordRuleService` + `GET/PUT /api/v1/rules/fraud-record-rule` (referente)
y `GET /api/v1/rules/internal/fraud-record-rule` (motor). Cada cambio deja snapshot en el historial.

**cases-service** — `POST /api/v1/cases/{id}/fraud-record` (solo `PENDING_ANALYST_REVIEW` o
`REJECTED`; desde `APPROVED` no, pagar y marcar fraude se contradicen) y
`GET /api/v1/cases/{id}/fraud-record/insured`. Escribe `Case.fraudDetermined` y valida contra el
veredicto guardado que el respaldo pericial exista de verdad.

**SQL** — tabla + índice + factor `fraud_history` (peso 0.60) + regla sembrada activa con ventana de
60 meses y veto encendido, en `db/init-multitenant.sql`, y
`db/migrations/2026-08-17-antecedente-fraude.sql` para Railway (idempotente, **depende de** la
migración del peritaje porque referencia `expert_assessment`).

**Tests** — 20 unitarios nuevos: `FraudHistoryEvaluatorTest`, `FraudRecordRuleEvaluatorTest`,
`InsuredFraudRecordServiceTest`, `FraudRecordServiceTest`.

## Lo que falta (frontend)

1. **Acción de confirmar el antecedente** en el detalle del expediente, al lado del peritaje: elegir
   origen y escribir el motivo. Con peritaje `FRAUD_CONFIRMED` presente, `EXPERT_BACKED` debería ser
   el default; sin él, la opción tendría que estar deshabilitada y no fallar recién en el submit.
2. **Alerta del antecedente vigente** al abrir un expediente cuyo asegurado ya tiene uno. Hay que
   distinguir visualmente los tres estados que devuelve el endpoint: vigente y puntúa, vigente sin
   respaldo pericial (`scores=false`), y vencido (`inForce=false`).
3. **Sección del referente** para la ventana y el veto (`fraud-record-rule`), en la pantalla de
   reglas. Sin esto la política solo se cambia por API.
4. **Label del factor `fraud_history`** en `core/models/business-rules.ts`, o el panel de scoring lo
   muestra con el código crudo.

## Contexto operativo

- La base compartida (Railway) **ya tiene aplicada** la migración `db/migrations/2026-08-17-peritaje.sql`.
- Los expedientes 2 y 11 de BBVA (Julián Pérez) tienen peritajes de prueba: el 2 con fraude
  confirmado y el 11 con fraude descartado. Sirven como datos de partida para este flujo.
- Umbrales sembrados: $500.000 para Celulares, $700.000 para Tecnología Portátil.
- Los peritos de demo apuntan a `perito.arbiter@gmail.com` (el mail sale de verdad por SendGrid).
- **Antes de desplegar el código del antecedente**, aplicar
  `db/migrations/2026-08-17-antecedente-fraude.sql` sobre Railway: los servicios corren con
  `ddl-auto=validate` y sin la tabla classification-service no levanta.
- El expediente 2 de BBVA (Julián Pérez, peritaje con fraude confirmado) es el candidato natural
  para probar el flujo entero: registrar el antecedente ahí y ver qué le pasa a su denuncia siguiente.
