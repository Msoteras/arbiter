# Caso de prueba manual — Reglas duras configurables + auditoría

Verifica los cuatro criterios de aceptación de la historia de umbrales de reglas duras:

1. el referente ve y edita cada umbral desde el panel;
2. un cambio de umbral impacta en la **próxima clasificación**, sin reiniciar servicios;
3. cada regla evaluada deja fila en `rule_result` (PASS y FAIL);
4. una aseguradora sin la regla configurada no la evalúa.

Más el gate de alta: una denuncia sobre una póliza sin cobertura **no crea expediente**.

Todo lo de acá abajo está armado contra el seed actual (`db/init-multitenant.sql` +
`db/seed-demo.sql`, el par canónico — ver `scripts/db-railway.ps1`; no `db/datos-aseguradoras.sql`,
que apunta al schema viejo pre-multitenant y no se usa), no contra el mock. Ids y fechas verificados
sobre el script, 13/08 y 15/08/2026 (alcance aseguradora de vigencia/mora + `onArrears`, id 14).

La base de Railway se seedeó originalmente desde una versión vieja del script, previa a esta
historia, así que le faltaban las reglas duras — se cargaron a mano por SQL directo (15/08),
con ids corridos (5-15 en bbva, 6-14 en provincia) porque la tabla ya tenía filas de antes
(`FAST_TRACK`, `COVERAGE_EXCLUSION`, etc.) ocupando los ids bajos que el script usa para una base
nueva. Los ids de §1 reflejan Railway tal como quedó, no los del script.

---

## 0 · Los datos del seed que vamos a usar

| Qué | Valor | Dónde está |
|---|---|---|
| Tenant | `arbiter_bbva` | `init-multitenant.sql` |
| Asegurado | **Martina Soteras**, DNI `42.987.654` — `asegurado.arbiter@gmail.com` | `arbiter_bbva.insured` id 1 |
| Póliza | **`POL-CEL-2026-042`**, vigencia **01/01/2026 → 01/01/2027**, al día, 0 siniestros previos | `aseguradora.poliza` id 1 |
| Cobertura | `coverage` id **1** = "Robo de celular", suma asegurada **$1.300.000**, carencia **30 días**, plazo denuncia 72 hs, tope 2 eventos/año | `arbiter_bbva.coverage` |
| Ramo / hecho generador | Celulares (`branch` 1) / "Robo en vía pública" (`claim_cause` **2**) | `arbiter_common` |
| Reglas duras | `insurer_rule` **5, 6, 7, 8** para la cobertura 1 (por cobertura) + **14** vigencia (toda la aseguradora) (ver §1) | seed nuevo |
| Referente | `referente.arbiter@gmail.com` | — |
| Analista | `analista.arbiter@gmail.com` | — |

> **Ojo con la agenda documental.** Está sembrada **por ramo**, y Celulares exige los 4 documentos
> (`police_report`, `purchase_proof`, `imei_deregistration`, `last_connection`). El gate de faltantes
> corre **antes** que las reglas temporales: si falta uno, la clasificación devuelve
> `FALTA_DOCUMENTACION` y **las reglas duras nunca se evalúan** — no vas a ver ninguna fila en
> `rule_result` y vas a creer que está roto. Subí los cuatro (sirve cualquier PDF/JPG).

> **Póliza a usar.** Martina también aparece con `POL-TEC-2026-050` y `POL-TEC-2026-515` en la BD
> Aseguradora, pero **ninguna de las dos está en el snapshot local** (`arbiter_bbva.policy` /
> `arbiter_provincia.policy`), así que el alta falla con 422 "No policy matching". Es un hueco
> preexistente del seed, no de esta historia. Usá `POL-CEL-2026-042`.

**Token para curl/Swagger:** el JWT vive solo en memoria en el front (`auth-session.service.ts`),
así que logueate en la UI y copiá el header `Authorization` de cualquier request desde la pestaña
Network.

---

## 1 · Punto de partida — las reglas ya vienen sembradas

Cada tenant arranca con cinco reglas duras **activas**, para que el comportamiento no cambie
respecto de antes de la historia — cuatro por cobertura y una para toda la aseguradora (§9 explica
por qué vigencia no es por cobertura; ver también `RuleType#insurerScoped()`):

```sql
-- Por cobertura (cobertura 1):
SELECT id, rule_type, active, blocks_fast_track, configuration
FROM arbiter_bbva.insurer_rule
WHERE coverage_id = 1 AND rule_type IN
  ('WAITING_PERIOD','REPORT_DEADLINE','POLICE_DEADLINE','MAX_EVENTS_YEAR')
ORDER BY id;

-- Toda la aseguradora:
SELECT id, rule_type, active, blocks_fast_track, configuration
FROM arbiter_bbva.insurer_rule
WHERE branch_id IS NULL AND coverage_id IS NULL AND rule_type IN ('POLICY_IN_FORCE','POLICY_STANDING');
```

Esperado: la primera trae ids **5, 6, 7, 8**, todas `active = true`, `blocks_fast_track = true`, y
solo `POLICE_DEADLINE` (id 7) con configuración: `{"deadlineHours": 72}`. El resto en `{}` — su
umbral es la columna de `coverage`, no la regla. La segunda trae una sola fila: id **14**
`POLICY_IN_FORCE`, `active = true` — `POLICY_STANDING` no aparece, arranca sin sembrar (§9).

**Criterio 1 (parte a):** como referente, ir a **Reglas → Celulares → solapa Coberturas**. Dentro de
la card de "Robo de celular", abajo de las exclusiones, tiene que estar el bloque *"Reglas duras que
evalúa el motor"* con los **cuatro** chips en activo (carencia, plazo de denuncia, plazo policial,
tope de eventos) y el campo **"Plazo de la denuncia policial (horas)"** en 72. Vigencia y mora **no**
están acá: son de toda la aseguradora, no del ramo, así que viven en su propio recuadro del
sidebar, **"Hard Stop"** — junto a "Scoring de riesgo", no como solapa dentro del ramo — que
muestra "Vigencia de la póliza" activa y "Mora de la póliza" inactiva. (Arrancó como solapa dentro
del ramo y se movió al sidebar el 15/08: como solapa daba a entender que era una config por ramo,
cuando en realidad es una sola fila por regla para toda la aseguradora.)

---

## 2 · Preparar la aseguradora (referente)

Login como `referente.arbiter@gmail.com`.

### 2.1 · Coberturas — ampliar el plazo de denuncia a la aseguradora

Solapa **Coberturas**, cobertura "Robo de celular": **Plazo de denuncia (días) = 30**.

> Por qué: el plazo de denuncia a la aseguradora (D11) y el de la denuncia policial (D12) son dos
> reglas distintas con dos relojes distintos, y las dos arrancan en 72 hs. Con las dos en 72 es
> **imposible** construir un caso donde la policial falle y la otra pase: si la denuncia policial cae
> fuera de las 72 hs del hecho, la denuncia a la aseguradora —que es todavía más tarde— también.
> Poniendo 30 días en la de la aseguradora, la única regla que puede fallar es la policial, que es la
> que queremos aislar.

Guardar coberturas.

### 2.2 · Fast Track — sin esto nada da FAST_TRACK

Las dos filas `FAST_TRACK` del seed (ids 1 y 2) están **sin `coverage_id`**, y
`FastTrackRuleService.getByCoverage()` filtra por cobertura: nunca matchean. Out of the box ninguna
cobertura tiene Fast Track funcional.

Solapa **Fast Track**: monto reclamado máx. **50%**, siniestros previos máx. **0**, exige póliza al
día **sí**. Guardar (hace fan-out a las dos coberturas del ramo).

### 2.3 · Verificar que quedó persistido

```bash
curl "http://localhost:8081/api/v1/rules/hard-rules?branchId=1&coverageId=1" \
  -H "Authorization: Bearer $TOKEN_REFERENTE"
```

Devuelve las **cuatro** coverage-scoped, con `POLICE_DEADLINE` en `enabled: true, deadlineHours:
72`. Vigencia no sale acá — es `GET /api/v1/rules/insurer-hard-rules` (sin `branchId`/`coverageId`,
ver §9).

---

## 3 · Denuncia 1 — la policial llega tarde (POLICE_DEADLINE = 72)

Login como `asegurado.arbiter@gmail.com` (Martina) → **Nueva denuncia**.

Las fechas van **relativas a hoy**, para que el caso no se pudra: lo único que importa es que el
hecho caiga dentro de los 30 días del plazo de denuncia y que la policial quede a más de 72 hs del
hecho.

| Campo | Valor |
|---|---|
| Póliza | `POL-CEL-2026-042` |
| Hecho generador | Robo en vía pública |
| Bien | Samsung Galaxy A56 |
| **Fecha y hora del hecho** | **hace 10 días**, 20:00 |
| **Fecha y hora de la denuncia policial** | **hace 5 días**, 10:00 → 110 hs después del hecho |
| Monto reclamado | **285000** |
| Documentación | los 4 del ramo |

*(Corriéndolo el 13/08/2026: hecho el **03/08 20:00**, policial el **08/08 10:00**.)*

La denuncia policial cae **110 hs** después del hecho (4 d 14 h), o sea fuera de las 72 configuradas.
El hecho está dentro de la vigencia (01/01/2026–01/01/2027) y fuera de la carencia (que venció el
31/01/2026), así que el alta pasa el gate y el expediente **sí** se crea.

> **No estires el "hace 10 días".** `reportedAt` es el momento del alta (`@CreationTimestamp`), así
> que D11 compara *ahora − hecho* contra las 720 hs que configuraste en §2.1: con el hecho a más de
> 30 días, esa regla también falla y el §4 nunca llega a `FAST_TRACK`.

**Esperado:**

- La clasificación **no** es `FAST_TRACK` — la regla lo bloquea y el caso va al LLM.
- Entre los factores/motivos que ve el analista:
  `Denuncia policial fuera de plazo: 110 hs desde el hecho, supera el máximo de 72 hs`.
- **Criterio 3** — la auditoría, con las cinco reglas y sus resultados:

```sql
SELECT rr.rule_type, rr.result, rr.evaluated_value, rr.rule_id
FROM arbiter_bbva.rule_result rr
WHERE rr.case_id = :caseId
ORDER BY rr.rule_id;
```

| rule_type | result | evaluated_value (aprox.) |
|---|---|---|
| `POLICY_IN_FORCE` | **PASS** | `eventDate=<hecho> vigencia=2026-01-01..2027-01-01` |
| `WAITING_PERIOD` | **PASS** | `eventDate=<hecho> carencia=30d desde 2026-01-01` |
| `REPORT_DEADLINE` | **PASS** | `reportedAt=+240h max=720h` |
| `POLICE_DEADLINE` | **FAIL** | `policeReportAt=+110h max=72h` |
| `MAX_EVENTS_YEAR` | **PASS** | `eventos12m=1 max=2` |

Lo importante son **las cuatro filas PASS**, no la FAIL: una tabla que solo guarda rechazos no prueba
que las demás reglas se evaluaron, que es justamente lo que pide la Disposición SSN 2/2023.

> Este caso llama al LLM, así que necesita **Ollama levantado** (o el perfil con
> `MockClaimClassifier`). Si Ollama está caído, el expediente queda en `CLASSIFICATION_FAILED` y las
> filas de `rule_result` igual no se escriben — la persistencia es posterior a la clasificación.

---

## 4 · Cambiar el umbral y volver a denunciar (criterio 2)

Como referente, en el mismo bloque de la solapa Coberturas: **Plazo de la denuncia policial = 168**
horas. Guardar coberturas. **No reiniciar nada.**

Verificar el snapshot de auditoría del cambio, que es parte del contrato de la pantalla:

```sql
SELECT h.changed_at, h.reason, h.config_version
FROM arbiter_bbva.insurer_rule_history h
WHERE h.rule_id = 7 ORDER BY h.changed_at DESC LIMIT 1;
```

Esperado: `config_version = {"deadlineHours":72}` (la versión **que se pisó**, no la nueva) y el
`reason` nombrando al referente.

Ahora **la misma denuncia otra vez**, con las mismas dos fechas del §3.

**Esperado:**

- Clasificación **`FAST_TRACK`** — 110 hs ahora entran en las 168, y con eso se destraba el gate:
  monto 285.000/1.300.000 = **21,9%** (< 50%), 0 siniestros previos, póliza al día.
- `rule_result` del nuevo caso: **`POLICE_DEADLINE` = PASS**, `evaluated_value` con `max=168h`.
- El expediente **no** queda resuelto: sigue en revisión del analista. El Fast Track agiliza, no
  automatiza (CLAUDE.md #5).

Esto es el criterio 2 completo: mismo input, distinto resultado, sin deploy y sin reiniciar.

---

## 5 · Apagar una regla (criterio 4)

Como referente, apagar el chip **"Plazo de la denuncia policial"**. Guardar.

```sql
SELECT rule_type, active FROM arbiter_bbva.insurer_rule WHERE id = 7;   -- active = false
```

Repetir la denuncia del §3 (policial 110 hs después) pero con el plazo policial **de nuevo en 72** y
la regla **apagada**.

**Esperado:**

- `rule_result` del caso tiene **4 filas**, sin ninguna de `POLICE_DEADLINE`.
- Ningún motivo menciona la denuncia policial: la regla no se evaluó, no es que la haya aprobado.
- Es lo mismo que le pasa a una aseguradora que nunca la configuró — apagar y no tener fila son
  equivalentes para el motor. Para simularlo desde cero:
  `DELETE FROM arbiter_bbva.insurer_rule WHERE id = 7;`

Volver a prenderla antes de seguir.

---

## 6 · Gate de alta — la denuncia que no llega a ser expediente

Los dos casos usan la **misma póliza** `POL-CEL-2026-042` y solo cambian la fecha del hecho.

### 6.1 · Hecho anterior a la vigencia

Fecha del hecho: **20/12/2025**. La póliza arranca el 01/01/2026.

**Esperado:**
- En el wizard, el aviso aparece **en el paso 2**, apenas cargás la fecha, y **"Siguiente" queda
  deshabilitado**: *"La póliza POL-CEL-2026-042 recién entró en vigencia el 01/01/2026, así que un
  siniestro anterior a esa fecha no está cubierto y no vamos a poder registrar la denuncia."*
- Si igual se manda por API, el backend responde **422** con el mismo motivo.
- **No se crea expediente.** Comprobalo:
  `SELECT count(*) FROM arbiter_bbva.cases WHERE occurred_at::date = '2025-12-20';` → **0**.
  (Las dos fechas de §6 sí son absolutas: dependen de la vigencia de la póliza, que es fija.)

### 6.2 · Hecho dentro de la carencia

Fecha del hecho: **15/01/2026**. La póliza está vigente (arrancó el 01/01), pero la carencia de 30
días de la cobertura recién da cobertura desde el **31/01/2026**.

**Esperado:** **422**, sin expediente:
*"La cobertura tiene una carencia de 30 días desde el alta de la póliza (2026-01-01): recién cubre
siniestros ocurridos a partir del 2026-01-31."*

> Este caso **no** se adelanta en el paso 2 y aparece recién al enviar: el portal no recibe
> `waitingPeriodDays` (es de la cobertura, y `GET /policies` devuelve la póliza). Es una limitación
> conocida, anotada en el javadoc del componente.

### 6.3 · Denuncia policial incoherente

Hecho **hace 2 días** 20:00, denuncia policial **hace 3 días** 09:00 (anterior al hecho).

**Esperado:** aviso en el paso 2 y 422 si se fuerza por API — *"La denuncia policial no puede ser
anterior al siniestro."* No se crea expediente.

---

## 7 · Resumen de qué prueba cada caso

| § | Criterio de aceptación | Prueba |
|---|---|---|
| 1 | 1 — el referente ve cada umbral | Las 4 filas por cobertura (solapa Coberturas) + la de vigencia (recuadro Hard Stop del sidebar) |
| 2.1 / 4 | 1 — y los edita | Plazo de denuncia por cobertura · plazo policial por regla |
| 4 | 2 — impacta sin reiniciar | Mismo input, 72 hs → LLM · 168 hs → FAST_TRACK |
| 3 | 3 — `rule_result` con PASS y FAIL | 5 filas, 4 PASS + 1 FAIL |
| 5 | 4 — sin regla, no se evalúa | 4 filas, sin `POLICE_DEADLINE` |
| 6 | (gate de alta: vigencia/carencia/fechas) | 422 y `cases` sin fila nueva |
| 9.2 | (gate de alta: mora, `onArrears=REJECT`) | 422 y `cases` sin fila nueva |

## 8 · Corte degradado — rules-service caído (nuevo, 13/08)

Cierra el pendiente de `plan-reglas-evaluables.md` §3. Con `RulesRestAdapter` propagando la falla en
vez de caer al mock (unit tests en `RulesRestAdapterTest`), falta la vuelta end-to-end con el stack
arriba:

1. Con el stack levantado, **bajar rules-service** (`docker stop` del contenedor, o matar el proceso
   si corre local).
2. Denunciar cualquier caso con `coverageId` real (cualquiera de los del §3 sirve).
3. **Esperado:** el POST igual devuelve 202 (la clasificación es async), pero `GET /claims/{id}`
   nunca trae `classification` — queda `null` indefinidamente mientras `@Retryable` reintenta (3
   veces, backoff 2s/4s/8s) y después se rinde. **No** debe aparecer ninguna fila nueva en
   `llm_analysis` ni en `rule_result` para ese caso: es la prueba de que no se coló el baseline del
   mock disfrazado de resultado real.
4. Con `arbiter.classification-refresh.interval-ms`/`max-attempts` en los valores del
   `application.yml` (20000 ms / 540), el expediente recién pasa a `CLASSIFICATION_FAILED` a las
   **3 horas** — la ventana se agrandó porque corriendo Ollama por CPU hay clasificaciones de 20
   minutos a más de una hora y el scheduler las daba por fallidas mientras seguían en curso. Para
   no esperar tanto en la prueba manual, bajar `max-attempts` a algo chico (ej. 3) vía variable de
   entorno antes de levantar cases-service.
5. Levantar rules-service de nuevo y, como analista o referente,
   `POST /api/v1/cases/{id}/retry-classification`. **Esperado:** esta vez sí clasifica con las
   reglas reales de la BD (no el mock), y quedan las filas de `rule_result` correspondientes.

## 9 · Mora — sexta regla dura, alcance aseguradora (nuevo, 13-15/08)

Mar consultó con Mich (equipo de negocio real) cómo se maneja la mora hoy: *"a las 3 cuotas
impagas se cancela el certificado; con 2 impagas y el siniestro dentro del período impago se
rechaza; con 1 impaga se espera al mes siguiente"* — y remarcó que, en términos generales, un
siniestro con mora **no se debería rechazar de una**, tiene que quedar a la espera.

Ese nivel de detalle (contar cuotas, saber en qué período cae el hecho) necesita un ledger de
cuotas con fechas de período que hoy no existe en ningún esquema — las cuotas no van del 1 al 31,
van de la fecha de alta a la fecha de alta. Lo que sí se cerró: la mora pasó a ser una **sexta
regla dura** (`POLICY_STANDING`). Sin umbral propio — el dato de origen es `policy.upToDate()`, que
ya viene de la BD Aseguradora.

**Alcance: toda la aseguradora, no por cobertura (15/08).** El primer diseño la sembraba como una
fila de `insurer_rule` por (rama, cobertura), igual que carencia o tope de eventos. Eso estaba mal:
una póliza está o no está en mora sin importar bajo qué cobertura se reclame — el HAR real de BBVA
lo confirma para el caso hermano (vigencia): el alta rechaza contra la "Operación Siniestrada"
completa, sin cobertura en el payload. `POLICY_IN_FORCE` y `POLICY_STANDING` pasaron a vivir en
`insurer_rule` con `branch_id` y `coverage_id` ambos `NULL`, y tienen su propio recuadro en el panel
del referente — **"Hard Stop"**, en el sidebar junto a "Scoring de riesgo", con un ícono de info
explicando que son las únicas reglas que pueden impedir que el expediente llegue a existir. No es
una solapa dentro del ramo (arrancó siéndolo el 13/08, pero eso daba a entender que era una config
por ramo cuando en realidad es una sola fila por regla para toda la aseguradora — se movió al
sidebar el 15/08, mismo criterio que ya usaba Scoring). El resto de las reglas duras (carencia,
plazos, tope de eventos) se quedaron por cobertura, porque su umbral es una columna de `coverage`.

**Nuevo (15/08): `onArrears`, decisión de la aseguradora.** Mich pidió standby, pero se resolvió
dejarlo configurable en vez de fijo — el referente elige, por regla, si la mora:
- **`STANDBY`** (default cuando se activa la regla, y el único comportamiento que existía hasta el
  13/08): el expediente se crea igual, la regla se evalúa en el motor durante la clasificación,
  bloquea Fast Track y queda auditada en `rule_result` — pero un analista siempre la revisa.
- **`REJECT`**: el gate de alta (`PolicyEligibilityValidator`, cases-service) rechaza la denuncia
  **antes de crear el expediente**, mismo criterio que vigencia y carencia (§6): no es una
  recomendación, es la inexistencia de las condiciones para reclamar. Ningún analista la ve, porque
  no llega a existir un caso.

**Arranca APAGADA en el seed** (no hay fila `insurer_rule` para `POLICY_STANDING`): antes de esta
historia la mora nunca bloqueaba nada por su cuenta —solo era un criterio opcional de Fast Track
(`requiresUpToDatePolicy`) que el referente podía dejar sin marcar—, así que activarla por default
habría sido un cambio de comportamiento real para casos en curso, no una migración neutra como las
otras cinco.

`aseguradora_bbva.poliza` **`POL-CEL-2025-201`** (Julián Pérez, DNI `30.555.777`) tiene
`estado_pago = 'SUSPENDIDA'` en `db/seed-demo.sql` — es la póliza en mora que ya trae el seed
canónico, pensada para ejercitar `policy_standing` en el scoring. Sirve para los dos casos de
abajo. Julián **sí tiene login real sembrado** (`asegurado2.arbiter@gmail.com`, user id 5), así que
se puede probar directo desde la UI sin workarounds.

### 9.1 · `onArrears = STANDBY` (motor, vía Fast Track/scoring)

1. Como referente, en el recuadro **Hard Stop** del sidebar (junto a Scoring de riesgo, no dentro
   de ningún ramo), prender **"Mora de la póliza"** y dejar el selector en **"Dejar en
   standby"**. Guardar.
2. Login como `asegurado2.arbiter@gmail.com` (Julián) → Nueva denuncia con `POL-CEL-2025-201` —
   cualquier hecho generador y fecha razonable, con los 4 documentos.
3. **Esperado:**
   - **Se crea el expediente** (esto es lo que distingue STANDBY de REJECT).
   - `blocksFastTrack = true` — el caso no da `FAST_TRACK` aunque el resto de los criterios
     (monto, sin previos) den bien.
   - Entre los factores: *"The policy has an outstanding balance — how many installments are
     unpaid and whether the event falls within an unpaid billing period needs the analyst's
     judgment call."* (el mensaje sale del motor en inglés — como todo lo que no es texto de
     negocio persistido, no está traducido al frontend todavía; si hace falta mostrarlo en
     castellano en la bandeja, es un ajuste de mapeo del lado del front, no del motor).
   - `rule_result` tiene una fila `POLICY_STANDING` en `FAIL`.
   - El expediente sigue yendo al analista (LLM o revisión), nunca se cierra solo — la mora
     "queda a la espera" en el sentido de que un humano la mira, no en el sentido de un estado
     nuevo que la pausa sin que nadie la vea. Ver la nota de alcance abajo.
4. Apagar la regla y repetir: `rule_result` no tiene fila `POLICY_STANDING`, y si el resto de los
   criterios da bien, ahí sí puede dar `FAST_TRACK` — el comportamiento pre-historia, intacto.

### 9.2 · `onArrears = REJECT` (gate de alta, sin expediente)

1. Como referente, en el recuadro **Hard Stop** del sidebar, con **"Mora de la póliza"** prendida, cambiar el
   selector a **"Rechazar en el alta"**. Guardar.
2. Denunciar con `POL-CEL-2025-201` / Julián, igual que en 9.1.
3. **Esperado:** **422**, mismo patrón que §6 — *"La póliza POL-CEL-2025-201 tiene un saldo
   pendiente de pago, así que no se puede iniciar el expediente hasta que se regularice."*
   **No se crea expediente**:
   `SELECT count(*) FROM arbiter_bbva.cases c JOIN arbiter_bbva.policy p ON p.id = c.policy_id
   WHERE p.external_policy_number = 'POL-CEL-2025-201';` → mismo conteo que antes del intento.
4. Volver a `STANDBY` y repetir: ahora sí se crea el expediente y cae en el flujo de 9.1.

## 10 · Lo que este caso NO cubre

- **Los tres niveles de mora** (1/2/3 cuotas, período de facturación anclado a la fecha de alta):
  necesitan el ledger de cuotas que no existe. Historia aparte si se quiere modelar entero — la
  regla de hoy es "mora sí/no", no "cuántas cuotas y desde cuándo". Ninguno de los dos modos
  (`STANDBY`/`REJECT`) lo resuelve: los dos siguen mirando el mismo booleano.
- **Un estado de expediente que lo deje realmente "en espera" sin pasar por el analista, en modo
  `STANDBY`**: no se construyó (sería una máquina de estados nueva — cuándo se reactiva, si el
  analista lo ve mientras tanto, etc.). En `STANDBY` la regla bloquea Fast Track y deja el hallazgo
  auditado; el caso sigue el camino normal (LLM o revisión manual), nunca se resuelve solo. En
  `REJECT` no hace falta ese estado nuevo porque directamente no hay expediente — ver §9.2.
- **Una aseguradora realmente sin ninguna regla configurada**: los dos tenants del seed arrancan
  con las cuatro reglas coverage-scoped (carencia/plazos/eventos/plazo policial) y `POLICY_IN_FORCE`
  activas; `POLICY_STANDING` sigue apagada. El §5 simula "sin configurar" apagando/borrando una
  fila, que es equivalente para el motor.
- **`REJECT` sin analista de por medio en absoluto**: es la misma categoría que vigencia y
  carencia (§6) — un rechazo determinístico contra un hecho objetivo (`policy.upToDate()`), no una
  recomendación del modelo. No es una excepción al human-in-the-loop del punto 5 de CLAUDE.md por el
  mismo argumento que ya vale para esas dos: la regla no resuelve un caso, evita que nazca uno. El
  relevamiento real de Mich prefiere `STANDBY` — `REJECT` es una opción que el referente puede
  elegir, no la recomendación de negocio por default.

## 11 · Precheck de elegibilidad — bloquear antes de completar el wizard (nuevo, 15/08)

Hasta acá, §6 y §9.2 describen el gate en el **submit final** (paso 3, con los 4 documentos ya
subidos): el asegurado llenaba todo el wizard y recién ahí se enteraba, por el 422, de que la
denuncia no se podía registrar. Nuevo endpoint `POST /api/v1/cases/eligibility` (rol ASEGURADO)
corre exactamente la misma validación (`PolicyEligibilityValidator`: vigencia, carencia, mora) sin
crear nada, y el wizard lo llama apenas hay póliza + fecha del hecho — bloquea "Siguiente" en el
**paso 2**, antes de subir un solo documento.

No reemplaza el gate del submit (`POST /cases` lo sigue corriendo igual): es el mismo chequeo,
adelantado. Si el precheck no responde (rules-service caído, red), el wizard **no bloquea** — cae
abierto, y el gate real del submit sigue estando.

### Caso de prueba — Diego Sosa, `onArrears=REJECT` (el mismo de §9.2, ahora en el paso 2)

1. Referente: Hard Stop → Mora activa, `onArrears=REJECT` (§9.2, paso 1).
2. Asegurado: iniciar denuncia con `POL-CEL-2026-077` (Diego Sosa, DNI `41.333.999`). Elegir hecho
   generador y cargar fecha del hecho — **sin llegar todavía al paso de documentación**.
3. **Esperado:** apenas se completa la fecha (con un debounce corto, ~400ms), "Siguiente" se
   deshabilita y aparece el mismo mensaje del backend que en §9.2 ("La póliza POL-CEL-2026-077 tiene
   un saldo pendiente de pago…"). **No se llega al paso 3**, no hace falta subir nada para
   confirmar el rechazo.
4. Volver a `STANDBY` (o apagar la regla) y repetir: "Siguiente" se habilita, aparece el badge
   informativo "Póliza con premios adeudados" (no bloqueante), y el wizard deja avanzar hasta el
   submit normal (§9.1).

### Caso de prueba — hechos generadores recortados por cobertura

1. Asegurado: iniciar denuncia con `POL-CEL-2026-042` (cobertura "Robo de celular", branch
   Celulares).
2. En "¿Qué te pasó?", revisar las opciones ofrecidas.
3. **Esperado:** **"Hurto" no aparece** en la lista — la cobertura "Robo de celular" lo excluye
   (`insurer_rule` id 3 del seed, `COVERAGE_EXCLUSION`, ver `db/init-multitenant.sql` línea ~737).
   El resto de los hechos generadores del ramo Celulares sí aparecen (la exclusión es lista negra,
   no blanca).
4. Contraprueba: elegir una póliza cuya cobertura no tenga ninguna `COVERAGE_EXCLUSION` configurada
   → el selector muestra todos los hechos generadores del ramo, sin recortar nada.

### Lo que este caso NO cubre

- El precheck y el gate del submit son **dos llamadas independientes** al mismo
  `PolicyEligibilityValidator`: no hay una sola verdad cacheada entre el paso 2 y el paso 3. Si algo
  cambia en el medio (el referente apaga la regla mientras el asegurado está completando el
  wizard), el paso 2 pudo haber mostrado un estado que ya no es el vigente al llegar al submit — es
  una ventana chica, no se resolvió con un lock ni con revalidación forzada antes de enviar.
- El caso de rules-service caído durante el precheck (fail-open, no bloquea) está descripto pero no
  probado a mano — necesita bajar rules-service a mitad del wizard, similar al §8 pero desde el
  lado del asegurado.
