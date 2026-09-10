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

## `llm_analysis` — tres columnas de la coherencia del hecho generador que el DER no tiene

**Encontrado:** 10/09/2026, corriendo `scripts/check-schema-consistency.py` contra la base desplegada.

`analisis_llm` en el DER tiene la recomendación, el modelo, la versión del prompt, la confianza, la
latencia y la fecha. La base desplegada tiene además tres columnas, agregadas a mano el 02/09/2026
antes de que ningún script las tuviera:

- `cause_consistency` — `MATCHES` / `AMBIGUOUS` / `CONTRADICTS` (con `CHECK`): si el relato del
  asegurado coincide con el hecho generador que declaró.
- `suggested_claim_cause` — el hecho generador al que apunta el relato cuando no es el declarado.
- `cause_evidence` — el pasaje del relato en el que se apoya esa lectura.

Es exactamente la lectura interpretativa que `CLAUDE.md` le asigna al modelo ("¿la denuncia
describe un robo o un hurto?"), y deja registrado el porqué como dato auditable, no solo en el
texto de las razones. El único análisis que las tiene cargadas es el de una denuncia de robo cuyo
relato dice "se me cayó el celu": `CONTRADICTS`, sugiere Caída.

Ya están en `db/init-multitenant.sql` y en `db/migrations/2026-09-10-coherencia-hecho-generador.sql`.
**Ningún código las escribe todavía**: el prompt vigente no las pide y la entidad no las mapea —
usarlas es una historia aparte (prompt, entidad y pantalla del analista).

**Acción:** agregar las tres columnas a `analisis_llm` en el DER, todas opcionales.

---

## Barrido del 10/09/2026 — lo que el `.mdj` no tiene

**Encontrado:** 10/09/2026, comparando `docs/arbiter der.mdj` (última edición: 02/08/2026) entidad
por entidad contra `db/init-multitenant.sql`. Todo lo que se construyó desde mediados de agosto
quedó afuera del `.mdj`. Las seis entradas que siguen son ese faltante, con los tipos exactos
(sacados del catálogo de un Postgres armado con el `init`) y un **nombre sugerido para el DER** en
el estilo del resto del diagrama, para poder cargarlas sin volver al SQL.

Las tablas de `aseguradora_*` (la BD Aseguradora) quedan fuera a propósito: no son de Arbiter.

---

## `policy_coverage` / `cobertura_contratada` — la tabla existe y el DER no la tiene

**Encontrado:** 10/09/2026 (barrido). Nació el 01/09/2026 con `db/migrations/2026-09-01-policy-coverage.sql`.

Una póliza tiene varias coberturas, cada una con su propia suma asegurada y franquicia. El DER
todavía modela una sola: `poliza.suma_asegurada` y `poliza.cobertura_id`, dos columnas que **ya no
existen** — la migración las dropeó después de mudarlas acá. La definición del riesgo sigue en
`cobertura` (catálogo del referente); acá vive lo **contratado** en cada póliza.

| Columna | Tipo | Nulo | Restricciones | Nombre sugerido en el DER |
|---|---|---|---|---|
| `id` | BIGINT | no | PK | `id` |
| `policy_id` | BIGINT | no | FK → `policy.id`; UNIQUE (`policy_id`, `coverage_id`) | `poliza_id` |
| `coverage_id` | BIGINT | no | FK → `coverage.id` | `cobertura_id` |
| `display_order` | INTEGER | no | default 1 | `orden` |
| `sum_insured` | NUMERIC(15,2) | no | | `suma_asegurada` |
| `deductible_pct` | NUMERIC(5,2) | sí | | `franquicia_pct` |

**Acción:** agregar `cobertura_contratada` (poliza 1—N cobertura_contratada N—1 cobertura) y
**sacar** `suma_asegurada` y `cobertura_id` de `poliza`.

---

## `document_analysis`, `document_visual_finding`, `document_detail` — la extracción de los adjuntos

**Encontrado:** 10/09/2026 (barrido). Nacieron con `db/migrations/2026-08-18-datos-extraidos.sql`;
`brand`, `model` y `document_detail` se sumaron con `db/migrations/2026-09-07-datos-documento.sql` (H0007).

Lo que la pasada de visión leyó de cada adjunto. Hermana de `analisis_imagen`: esa es cómo **se ve**
la imagen (embedding, reutilización), esta es qué **dice** el documento. Todos los campos leídos son
nullable: NULL significa "el documento no lo dice", nunca "no coincide". Una fila por documento
(se reemplaza en cada reclasificación, a diferencia de `analisis_llm`, que es append-only).

**`analisis_documento`**

| Columna | Tipo | Nulo | Restricciones | Nombre sugerido en el DER |
|---|---|---|---|---|
| `id` | BIGINT | no | PK | `id` |
| `transcription` | TEXT | no | | `transcripcion` |
| `document_date` | DATE | sí | | `fecha_documento` |
| `amount` | NUMERIC(14,2) | sí | | `monto` |
| `item_description` | VARCHAR(255) | sí | | `descripcion_bien` |
| `brand` | VARCHAR(100) | sí | | `marca` |
| `model` | VARCHAR(100) | sí | | `modelo` |
| `imei` | VARCHAR(20) | sí | | `imei` |
| `affected_party` | VARCHAR(20) | no | CHECK: `TITULAR` / `FAMILIAR` / `TERCERO` / `DESCONOCIDO` | `damnificado` |
| `extracted_at` | TIMESTAMPTZ | no | default `now()` | `fecha_extraccion` |
| `case_document_id` | BIGINT | no | FK → `case_documents.id`; UNIQUE (1:1 con el documento) | `documento_expediente_id` |

**`hallazgo_visual_documento`** — signos de adulteración que vio el modelo, una fila cada uno
(mismo criterio que `razon_llm`). Vacío es lo normal.

| Columna | Tipo | Nulo | Restricciones | Nombre sugerido en el DER |
|---|---|---|---|---|
| `id` | BIGINT | no | PK | `id` |
| `finding` | TEXT | no | | `hallazgo` |
| `analysis_id` | BIGINT | no | FK → `document_analysis.id` | `analisis_documento_id` |

**`dato_documento`** — todo lo demás que dice el documento y ninguna regla lee (nro. de factura,
serie, comercio), como nombre/valor. Se muestra, nunca se compara.

| Columna | Tipo | Nulo | Restricciones | Nombre sugerido en el DER |
|---|---|---|---|---|
| `id` | BIGINT | no | PK | `id` |
| `name` | VARCHAR(100) | no | | `nombre` |
| `value` | VARCHAR(500) | no | | `valor` |
| `analysis_id` | BIGINT | no | FK → `document_analysis.id` | `analisis_documento_id` |

**Acción:** agregar las tres (documento_expediente 1—1 analisis_documento 1—N hallazgo / dato).

---

## `expert_firm` / `perito` y `expert_assessment` / `peritaje` — la derivación a perito

**Encontrado:** 10/09/2026 (barrido). Nacieron con `db/migrations/2026-08-17-peritaje.sql`;
`indemnifiable_amount` ya tiene su propia entrada más arriba.

`perito` es el directorio de estudios externos de cada aseguradora — **no son usuarios** de Arbiter
(sin cuenta ni rol: el analista les escribe por mail). `peritaje` es una derivación y el informe que
vuelve; copia nombre y mail del perito en vez de solo referenciarlo, para que el registro de quién
verificó este siniestro no cambie si se edita el catálogo (mismo criterio que `poliza_consultada`).

**`perito`**

| Columna | Tipo | Nulo | Restricciones | Nombre sugerido en el DER |
|---|---|---|---|---|
| `id` | BIGINT | no | PK | `id` |
| `name` | VARCHAR(120) | no | | `nombre` |
| `email` | VARCHAR(150) | no | | `email` |
| `zone` | VARCHAR(80) | sí | | `zona` |
| `active` | BOOLEAN | no | default `true` | `activo` |
| `branch_id` | BIGINT | sí | FK → `branch.id`; NULL = cubre todos los ramos | `rama_id` |

**`peritaje`**

| Columna | Tipo | Nulo | Restricciones | Nombre sugerido en el DER |
|---|---|---|---|---|
| `id` | BIGINT | no | PK | `id` |
| `expert_name` | VARCHAR(120) | no | | `nombre_perito` |
| `expert_email` | VARCHAR(150) | no | | `email_perito` |
| `reason` | TEXT | no | | `motivo` |
| `derived_at` | TIMESTAMPTZ | no | default `now()` | `fecha_derivacion` |
| `notified_at` | TIMESTAMPTZ | sí | | `fecha_notificacion` |
| `report_received_at` | TIMESTAMPTZ | sí | CHECK: va junto con `verdict` (los dos o ninguno) | `fecha_recepcion_informe` |
| `verdict` | VARCHAR(20) | sí | CHECK: `FRAUD_CONFIRMED` / `FRAUD_DISCARDED` / `INCONCLUSIVE` | `veredicto` |
| `verdict_note` | TEXT | sí | | `nota_veredicto` |
| `indemnifiable_amount` | NUMERIC(15,2) | sí | | `monto_indemnizable` |
| `derived_by` | BIGINT | no | FK → `claims_analyst.id` | `derivado_por` |
| `expert_firm_id` | BIGINT | sí | FK → `expert_firm.id` | `perito_id` |
| `report_document_id` | BIGINT | sí | FK → `case_documents.id` | `documento_informe_id` |
| `case_id` | BIGINT | no | FK → `cases.id`; UNIQUE (una derivación por expediente) | `expediente_id` |

**Acción:** agregar las dos.

---

## `insured_fraud_record` / `antecedente_fraude` — la tabla existe y el DER no la tiene

**Encontrado:** 10/09/2026 (barrido). Nació con `db/migrations/2026-08-17-antecedente-fraude.sql`.

La memoria que Arbiter tenía del expediente pero no de la persona: un asegurado con fraude
comprobado arrancaba su próxima denuncia como si nada. **Inmutable** (es una afirmación sobre una
persona: Disp. SSN 2/2023 y Ley 25.326); deja de contar por la ventana que configura la aseguradora
(`regla_aseguradora` `FRAUD_RECORD`), no por edición. `source` decide cuánto pesa: `EXPERT_BACKED`
puntúa y puede vetar Fast Track, `ANALYST_DECLARED` es solo alerta.

| Columna | Tipo | Nulo | Restricciones | Nombre sugerido en el DER |
|---|---|---|---|---|
| `id` | BIGINT | no | PK | `id` |
| `insured_dni` | VARCHAR(20) | no | | `dni_asegurado` |
| `source` | VARCHAR(20) | no | CHECK: `EXPERT_BACKED` / `ANALYST_DECLARED` | `origen` |
| `reason` | TEXT | no | | `motivo` |
| `declared_by_analyst_id` | BIGINT | no | FK → `claims_analyst.id` | `analista_declarante_id` |
| `declared_by_analyst_name` | VARCHAR(255) | no | | `nombre_analista_declarante` |
| `declared_at` | TIMESTAMPTZ | no | default `now()` | `fecha_declaracion` |
| `expert_assessment_id` | BIGINT | sí | FK → `expert_assessment.id`; CHECK: obligatorio si `EXPERT_BACKED`, NULL si `ANALYST_DECLARED` | `peritaje_id` |
| `case_id` | BIGINT | no | FK → `cases.id`; UNIQUE | `expediente_id` |

**Acción:** agregarla.

---

## Columnas sueltas en entidades que el DER sí tiene

**Encontrado:** 10/09/2026 (barrido).

| Entidad del DER | Columna | Tipo | Nulo | Default | Nombre sugerido | Qué es / de dónde viene |
|---|---|---|---|---|---|---|
| `asegurado` | `image_consent_version` | VARCHAR(20) | sí | | `version_consentimiento_imagenes` | Qué versión del texto de consentimiento aceptó (onboarding, `2026-08-24-onboarding-asegurado.sql`). |
| `asegurado` | `image_consent_at` | TIMESTAMPTZ | sí | | `fecha_consentimiento_imagenes` | Cuándo lo aceptó (ídem). |
| `asegurado` | `onboarding_complete` | BOOLEAN | no | `false` | `onboarding_completo` | Si terminó el primer ingreso (ídem). |
| `asegurado` | `onboarding_completed_at` | TIMESTAMPTZ | sí | | `fecha_onboarding` | Cuándo lo terminó (ídem). |
| `cobertura` | `clause` | VARCHAR(20) | sí | | `clausula` | Código de cláusula de la cobertura (09/08, commit `741a9c0c`). El propio `init` la marca "sin respaldo en el DER". |
| `cobertura` | `exclusions` | JSONB | sí | | `exclusiones` | Textos de exclusión que se inyectan en el prompt. **No** son las exclusiones por hecho generador (esas son `regla_aseguradora` `COVERAGE_EXCLUSION`). Ídem. |
| `poliza_consultada` | `total_amount_claimed` | NUMERIC(15,2) | sí | | `monto_total_reclamado` | Suma de lo indemnizado en siniestros previos, congelada junto al conteo (`2026-08-28-monto-historico-snapshot.sql`). Ver nota abajo. |
| `configuracion_scoring` | `full_analysis_on_fast_track` | BOOLEAN | no | `false` | `analisis_completo_en_fast_track` | Si el Fast Track corre igual el análisis pesado para tener el score completo (11/08, commit `d9906951`). |
| `configuracion_scoring` | `valid_from` | TIMESTAMPTZ | no | | `vigente_desde` | Desde cuándo rige la configuración. Está desde el script original (31/07). |
| `expediente` | `classification_failure_reason` | VARCHAR(20) | sí | | `motivo_fallo_clasificacion` | `INFRASTRUCTURE` / `OTHER` (sin CHECK): decide si el expediente se reencola solo (`db/migrate-classification-failure-reason.sql`, 28/08). |
| `expediente` | `classification_failure_message` | TEXT | sí | | `mensaje_fallo_clasificacion` | El detalle del fallo (ídem). |
| `notificacion` | `created_at` | TIMESTAMPTZ | no | `now()` | `fecha_creacion` | Cuándo se generó, aparte de cuándo se envió. Está desde el script original (31/07). |

**Nota sobre `total_amount_claimed`:** el 28/08 hubo dos scripts contradictorios.
`db/migrate-drop-policy-snapshot-total-amount-claimed.sql` la dropea por considerarla drift sin
uso, y `db/migrations/2026-08-28-monto-historico-snapshot.sql` la vuelve a crear para la solapa de
trazabilidad. Ganó la segunda: está en el `init`, en la base desplegada y en uso. El script de
drop quedó obsoleto —correrlo borraba datos— y se sacó del repo el 10/09/2026.

**Acción:** agregar las doce columnas a sus entidades.

---

## `factor` — el DER la tiene como entidad y es un enum

**Encontrado:** 10/09/2026 (barrido).

El `.mdj` tiene la entidad `factor` (`id`, `nombre`) y `peso_factor` apuntándole. La implementación
no tiene esa tabla: `factor_weight.factor_code` guarda el código del factor como texto y se matchea
contra `RiskFactorIds` en el código. El propio `init` lo dice ("`factor` is an enum in the DER, not a
table"), así que la decisión ya estaba tomada y el `.mdj` quedó atrás.

**Acción:** sacar la entidad `factor` del DER y dejar `peso_factor.factor_codigo` como código de un
enum (documentar los valores posibles, o referenciar `RiskFactorIds`).

---

## Plantilla para la próxima entrada

```
## `tabla.columna` — descripción corta del bache

**Encontrado:** fecha.

Qué dice el DER vs. qué hace el código/no hace nadie.

**Acción:** dropear / derivar / documentar / decisión pendiente (con quién hay que confirmarla).
```
