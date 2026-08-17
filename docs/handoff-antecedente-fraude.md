# Handoff — Antecedente de fraude (trazabilidad entre denuncias)

Continuación del trabajo de **derivación a peritaje**, que ya está terminado y mergeado en
`feature/fraude-derivacion-perito`. Este documento existe para retomar el segundo flujo desde cero
en otra conversación, sin tener que reconstruir el contexto.

Fecha: 2026-08-17.

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

## Preguntas abiertas

1. **¿Puede haber antecedente sin peritaje?** La usuaria dijo que sí (la lista negra). Falta definir
   qué evidencia mínima se le exige al analista para registrarlo, y cómo se muestra que ese
   antecedente es más débil que uno pericial.
2. **¿La ventana es global de la aseguradora o por ramo?** Con dos ramos, global parece suficiente.
3. **¿Dónde se registra?** Tabla propia por tenant (algo como `insured_fraud_record`) vs. escribir
   `Case.fraudDetermined` y derivar el antecedente de los expedientes. La tabla propia permite
   procedencia (qué expediente, qué informe, qué analista, qué fecha) y vigencia; la columna sola no.
4. **¿Qué pasa cuando el peritaje descarta el fraude?** También es información valiosa y protege al
   asegurado de que lo vuelvan a derivar por lo mismo. ¿Se registra como "peritaje realizado, sin
   hallazgo" o no se registra nada?
5. **¿El asegurado ve algo de esto?** Por lo resuelto en el peritaje, la respuesta por defecto es no
   — pero conviene decidirlo explícitamente porque acá el dato es más sensible.
6. **Ley 25.326 (datos personales).** Marcar a una persona como defraudador es un dato sensible con
   consecuencias. Vale al menos dejar dicho en la defensa por qué el antecedente exige respaldo
   pericial y por qué tiene vencimiento.

---

## Pendientes que quedaron del peritaje (no bloquean, pero conviene cerrarlos)

- **Contradicción front/back sobre el referente.** `POST /cases/{id}/decision` y los dos endpoints de
  peritaje están anotados `hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')`, así que el
  backend le permite decidir y derivar al referente; el frontend no le muestra los botones
  (`canAct()` compara contra `ANALISTA_SINIESTROS`). Hay que elegir un lado. La usuaria todavía no
  definió cuál.
- **Copy de la card de decisión para el referente**: hoy lee "Pendiente de decisión del analista",
  que parece que falta algo en vez de "esto no te toca".
- **Sugerir la derivación desde el scoring**: cuando la banda es `HIGH`/`CRITICAL` y el expediente es
  elegible, destacar el botón. Se acordó que la sugerencia salga del lado determinístico y **no**
  como un sexto valor de `Classification` — la decisión #6 fija cinco categorías y el mapeo de
  tipologías está construido sobre ellas.
- **Reintentos de clasificación**: se vieron expedientes con `"clasificación fallida tras 501
  reintentos"` y transiciones triplicadas en el historial. Es data anterior al fix de estabilización
  que trajo develop, pero si reaparece en expedientes nuevos hay que mirarlo.

---

## Contexto operativo

- La base compartida (Railway) **ya tiene aplicada** la migración `db/migrations/2026-08-17-peritaje.sql`.
- Los expedientes 2 y 11 de BBVA (Julián Pérez) tienen peritajes de prueba: el 2 con fraude
  confirmado y el 11 con fraude descartado. Sirven como datos de partida para este flujo.
- Umbrales sembrados: $500.000 para Celulares, $700.000 para Tecnología Portátil.
- Los peritos de demo apuntan a `perito.arbiter@gmail.com` (el mail sale de verdad por SendGrid).
