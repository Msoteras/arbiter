# Baches del DER

Registro de lugares donde el DER (`docs/arbiter der.mdj`) tiene una columna, una relación o un dato
que no se sostiene contra lo implementado — redundante, sin semántica definida, o de un modelo
viejo que quedó atrás. **No son historias de desarrollo**: son correcciones al propio DER (dropear
una columna, documentar una derivación, resolver una ambigüedad de una vez), y quedan afuera del
backlog de Trello por eso.

El DER sigue siendo la fuente de verdad (`CLAUDE.md`) — este documento no propone desvíos, es la
lista de qué hay que corregirle a él mismo cuando se lo vuelva a tocar.

Cada entrada: qué se encontró, por qué es un bache, y qué acción corresponde (dropear / derivar /
documentar / decisión pendiente).

---

## `arbiter_provincia`/`arbiter_bbva`.coverage — `is_individual` es la negación de `covers_family_group`

**Encontrado:** 26/08/2026, planificando el sprint 9.

El seed es consistente con esa lectura: `covers_family_group=FALSE` ⇔ `is_individual=TRUE` en las
dos coberturas configuradas. Son el mismo hecho guardado dos veces con signo cambiado.

**Confirmado por el equipo:** sí, es la negación.

**Acción:** dropear `is_individual` del DER (y de la tabla, si el drop no rompe nada que la lea) o
dejarla como columna derivada documentada explícitamente como tal — no mantener las dos vivas como
si fueran datos independientes.

---

## `cases.destination` — sin semántica definida, candidata a estar muerta

**Encontrado:** 09/08/2026 (barrido original) · revisado 26/08/2026.

`VARCHAR(40)` suelto en el DER, sin valores definidos en ninguna fuente (ni HU, ni paper, ni
código). `grep` sobre todo el código no devuelve ninguna referencia — nadie la lee ni la escribe.

**Actualización 26/08:** la funcionalidad que `destination` probablemente pretendía cubrir (derivar
un siniestro a investigación, marcarlo como pagado, etc.) ya se construyó por otro lado —
`FraudRecordService`/`cases.fraud_determined` para la determinación de fraude, `ExpertAssessment`
para la derivación a perito, los estados del ciclo de vida para el resto. `destination` no participa
de ninguno de esos flujos.

**Acción:** pendiente de confirmar con el equipo, pero la hipótesis de trabajo es dropearla —
quedó de un modelo anterior que la funcionalidad real terminó reemplazando por columnas y tablas
más específicas.

---

## `case_message` — la tabla existe y el DER no la tiene

**Encontrado:** 31/08/2026, al implementar la conversación entre el asegurado y el analista (H0034).

El DER modela `notificacion` (saliente y automática) pero ninguna entidad de conversación. La
implementación agregó `case_message` en cada esquema de aseguradora: `case_id`, `sender_id` →
`arbiter_common.users`, `sender_role` (INSURED/ANALYST, congelado al escribir), `body`,
`created_at`, `read_at`. Es una tabla por tenant, dueña de `cases-service`, y no reemplaza a
`notificacion`: esa sigue existiendo y ahora también registra los avisos de mensaje nuevo.

**Acción:** agregarla al DER. No hay ambigüedad que resolver ni decisión pendiente — es un
faltante, y el esquema ya está en `db/init-multitenant.sql` y aplicado.

---

## `case_settlement` — la tabla existe y el DER no la tiene

**Encontrado:** 01/09/2026, al implementar la determinación del monto a pagar (bloque 1).

El DER llega hasta `clasificacion_expediente` —la decisión del analista— y ahí se corta: no hay
ninguna entidad que diga **cuánto** se paga. Pero determinar el monto es un paso del procedimiento
de la compañía (NSIN001 §5.2.1.2, "Liquidación del Siniestro"), no un trámite posterior: el
analista lo fija junto con la aprobación.

La implementación agregó `case_settlement` en cada esquema de aseguradora, una fila por expediente
y solo cuando el analista confirmó. Guarda las **entradas** además del resultado (`sum_insured`,
`settlement_basis`, `replacement_value`, `deductible_rate`, `event_ordinal`, `event_percentage`,
`pending_installments`, `installment_amount`), cada deducción por separado, y los dos montos:
`calculated_amount` (lo que dio la fórmula) y `settled_amount` (lo que el analista autorizó), con
`adjustment_reason` obligatorio cuando difieren. Es la misma exigencia de la Disposición SSN 2/2023
que ya cumple `clasificacion_expediente`, aplicada a la plata.

Van con ella dos ampliaciones de tablas que el DER sí tiene:

- **`cobertura`** suma los parámetros que el referente configura para liquidar:
  `settlement_basis`, `second_event_percentage`, `deduct_pending_installments`,
  `deduct_overdue_balance`.
- **`poliza_consultada`** suma lo que la cuenta necesita congelado para ser reproducible:
  `effective_to`, `installment_amount`, `overdue_balance`, `events_in_year`.

Y un dato de origen: `aseguradora_*.poliza.importe_cuota`. `saldo_deuda` cubre lo ya vencido, no lo
que queda por vencer, y el manual de Celulares descuenta explícitamente "las cuotas pendientes de
pago".

**Acción:** agregarlas al DER. No hay ambigüedad que resolver — es un faltante, y el esquema ya
está en `db/init-multitenant.sql` con su migración en
`db/migrations/2026-09-01-determinacion-monto-a-pagar.sql`.

---

## `settlement_authority` — la tabla existe y el DER no la tiene

**Encontrado:** 02/09/2026, al implementar las atribuciones de liquidación (bloque 2).

Continúa la entrada anterior. Determinar el monto no es un acto libre del analista: el Anexo II
del procedimiento de la compañía le pone un techo —"Aprobar liquidaciones hasta el límite del
atributo asignado por rama"— y por encima de ese monto firma alguien de más arriba.

La implementación agregó `settlement_authority` en cada esquema de aseguradora: `branch_id`
(único), `max_amount`, `updated_at`. **Sin fila para un ramo no hay tope**, que es como funcionaba
antes: poner el límite es la acción explícita del referente.

Es **por rama y no por analista**, aunque el Anexo II cruce rama × nivel jerárquico: Arbiter tiene
un solo rol de analista, así que la dimensión del nivel no tiene sobre qué variar. Si aparecen
analistas senior y junior, la tabla suma una columna.

Va con ella la ampliación de `case_settlement`: `status` (`AUTHORIZED` / `PENDING_AUTHORIZATION` /
`RETURNED`), `authority_limit` (el tope congelado al confirmar), `authorized_by_user_id` →
`arbiter_common.users`, `authorized_at`, `return_reason` y `pending_justification`.

Dos decisiones que conviene que el DER refleje, porque no son obvias:

- **No hay estado nuevo de expediente.** El que espera autorización es la *liquidación*; el
  expediente sigue en `PENDING_ANALYST_REVIEW`. Es un control interno y el asegurado no tiene por
  qué verlo pasar por su línea de tiempo.
- **La aprobación no se registra hasta que se autoriza.** Por eso existe `pending_justification`:
  la justificación del analista queda en custodia y recién se convierte en
  `clasificacion_expediente` cuando el referente firma. Si se registrara antes, el expediente
  quedaría con un veredicto que no surtió efecto, y una devolución dejaría dos decisiones para un
  mismo siniestro.

**Acción:** agregarlas al DER. Esquema en `db/init-multitenant.sql`, migración en
`db/migrations/2026-09-02-atribuciones-de-liquidacion.sql`.

---

## `coverage.settlement_formula` y `expert_assessment.indemnifiable_amount` — dos columnas más

**Encontrado:** 06/09/2026, cerrando los bloques 3 y 5 de la determinación del monto.

Continúan las dos entradas anteriores; van juntas porque son de la misma historia.

- **`coverage.settlement_formula`** (`TOTAL_LOSS` / `REPAIR`): cómo se liquida un siniestro de esa
  cobertura. Existen dos fórmulas porque el bien no siempre desaparece — en el daño por tentativa
  queda dañado, se repara, y **la póliza no se extingue**, así que no se descuentan las cuotas a
  vencer. Está en la cobertura y no en el hecho generador porque el catálogo de la aseguradora ya
  las separa así ("Robo de celular" y "Hurto" son pérdidas, "Daño accidental" es daño) y
  `PolicyCoverageResolver` ya resuelve cuál responde por cada causa. `case_settlement.formula` pasó
  a aceptar los dos literales.
- **`expert_assessment.indemnifiable_amount`**: lo que el perito determinó que vale el siniestro.
  El procedimiento le pide verificar "la causa del siniestro como el monto indemnizable" (NSIN001
  §2.6) y Arbiter le venía registrando solo la causa. Lo carga el analista junto con el veredicto:
  el perito está fuera del sistema y contesta por mail. Nullable a propósito — un fraude confirmado
  no tiene nada que indemnizar, y un cero ahí diría otra cosa.

No hizo falta ninguna columna para el bloque 4: `document_analysis.amount` ya existía, el modelo ya
venía leyendo el importe de cada adjunto, y nadie lo estaba usando para liquidar.

**Acción:** agregarlas al DER. Migraciones en `db/migrations/2026-09-06-formula-de-reparacion.sql`
y `db/migrations/2026-09-06-monto-del-peritaje.sql`.

---

## `llm_analysis.cause_consistency` / `.suggested_claim_cause` / `.cause_evidence` — tres columnas que el DER no tiene

**Encontrado:** 01/09/2026, al implementar el cruce entre el relato del asegurado y el hecho
generador que declaró.

El DER modela `analisis_llm` con la recomendación, el modelo, la versión del prompt, la confianza y
la latencia; los motivos van a `razon_llm`. No tiene dónde guardar un veredicto sobre el **relato**:
si la descripción libre que escribió el asegurado se corresponde con el hecho generador que eligió
del selector. Las reglas duras evalúan el hecho **declarado**, así que quien elegía "Robo en vía
pública" (cubierto) y describía un hurto (excluido) pasaba el gate de exclusiones sin que nadie lo
notara.

La implementación agregó tres columnas a `llm_analysis` en cada esquema de aseguradora:
`cause_consistency` (`MATCHES`/`AMBIGUOUS`/`CONTRADICTS`, con CHECK), `suggested_claim_cause` (el
hecho que el relato describe, **por nombre y sin FK** a `arbiter_common.claim_cause`: la fila es
evidencia inmutable y tiene que seguir diciendo lo que el modelo contestó aunque el referente
después renombre ese hecho) y `cause_evidence` (la frase textual del asegurado que sostiene el
veredicto). Las tres son NULL en las corridas anteriores al chequeo, y NULL significa "no evaluado",
nunca `MATCHES`.

Van en `analisis_llm` y no en una tabla nueva porque salen de la **misma llamada al modelo** que la
recomendación: son parte del mismo registro auditable (Disposición SSN 2/2023), no un análisis
aparte.

**Acción:** agregarlas al DER. No hay ambigüedad que resolver — es un faltante, y el esquema ya
está en `db/init-multitenant.sql` y en `db/migrations/2026-09-01-consistencia-relato-hecho-generador.sql`.

---

## Plantilla para la próxima entrada

```
## `tabla.columna` — descripción corta del bache

**Encontrado:** fecha.

Qué dice el DER vs. qué hace el código/no hace nadie.

**Acción:** dropear / derivar / documentar / decisión pendiente (con quién hay que confirmarla).
```
