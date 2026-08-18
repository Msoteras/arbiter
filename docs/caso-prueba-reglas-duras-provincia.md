# Caso de prueba manual — Reglas duras, versión Provincia

Mismo caso que [`caso-prueba-reglas-duras.md`](caso-prueba-reglas-duras.md) (léelo primero — acá no
se repite el *por qué* de cada cosa, solo los datos y valores que cambian), armado para probar con
Provincia en vez de BBVA. Ids y estados verificados contra Railway en vivo el 17/08/2026 — **no**
contra el script: la base real diverge del script en dos puntos importantes, marcados abajo.

---

## 0 · Los datos de Provincia que vamos a usar

| Qué | Valor | Dónde está |
|---|---|---|
| Tenant | `arbiter_provincia` | — |
| Asegurada (camino feliz) | **Martina Soteras**, DNI `42.987.654` — login `asegurado.arbiter@gmail.com` | `arbiter_provincia.insured` id 1 |
| Póliza (camino feliz) | **`POL-CEL-2026-777`**, vigencia **01/04/2026 → 01/04/2027**, al día, 0 cuotas impagas | `aseguradora_provincia.poliza` id 3 |
| Asegurado (mora) | **Julián Pérez**, DNI `30.555.777` — login `asegurado2.arbiter@gmail.com` | `arbiter_provincia.insured` id 2 |
| Póliza (mora) | **`POL-CEL-2026-905`**, vigencia 01/02/2026 → 01/02/2027, `SUSPENDIDA`, 2 cuotas impagas ($18.200) | `aseguradora_provincia.poliza` id 5 |
| Cobertura | `coverage` id **1** = "Robo de celular", suma asegurada varía por póliza ($700.000 en la de Martina), carencia **30 días**, plazo denuncia policial 72 hs, tope 2 eventos/año | `arbiter_provincia.coverage` |
| Ramo / hecho generador | Celulares (`branch` **1**) / "Robo en vía pública" (`claim_cause` **2**) — mismos ids que en BBVA, es catálogo global | `arbiter_common` |
| Reglas duras por cobertura (cobertura 1) | `insurer_rule` **6** (WAITING_PERIOD), **7** (REPORT_DEADLINE), **8** (POLICE_DEADLINE, `{"deadlineHours":72}`), **9** (MAX_EVENTS_YEAR) — todas activas | ids **distintos** de BBVA (ahí eran 5-8) |
| Reglas duras, toda la aseguradora | `insurer_rule` **14** `POLICY_IN_FORCE` (activa) + **15** `POLICY_STANDING` (**activa, `onArrears: REJECT`**) | ver §0.2 |
| Referente | `referente.provincia.arbiter@gmail.com` | `arbiter_common.users` id 6 |
| Analista | `analista.provincia.arbiter@gmail.com` (Diego Fernández) | `arbiter_provincia.claims_analyst` id 1 |
| Documentación (Celulares · Robo en vía pública) | `police_report`, `purchase_proof`, `imei_deregistration`, `last_connection` — los 4 obligatorios | `arbiter_provincia.document_requirement` ids 1-4, mismo esquema que BBVA |

### 0.1 · Fast Track — sin configurar, igual que BBVA arrancó

`insurer_rule` ids **1, 2** (`FAST_TRACK`) tienen `configuration = null` y sin `coverage_id`: como en
BBVA antes del §2.2 del caso original, **ninguna cobertura tiene Fast Track funcional todavía**. Hay
que configurarlo desde la solapa **Fast Track** como referente antes de esperar un `FAST_TRACK` en
cualquier prueba de acá abajo — mismos valores que el caso de BBVA (monto máx. 50%, previos máx. 0,
póliza al día sí) sirven igual.

### 0.2 · Diferencia real con BBVA #1 — la mora ya está activa

A diferencia de BBVA (que arranca con `POLICY_STANDING` sin sembrar), **en Provincia ya está activa
con `onArrears = REJECT`** — quedó así de la sesión en que probamos "premios adeudados" con Julián.
Esto tiene dos consecuencias prácticas para todo lo de abajo:

- **Para el camino feliz (§1-§6 del caso original)**: usar una póliza que **no** esté en mora
  (`POL-CEL-2026-777`, Martina — `cuotas_impagas = 0`). Si usás una póliza en mora sin darte cuenta,
  el alta rechaza con 422 por `POLICY_STANDING` antes de llegar a probar lo que en verdad querés
  probar.
- **Para el equivalente del §9.2/§11 (mora, `onArrears=REJECT`)**: **ya podés probarlo directo**, sin
  ningún paso de setup del referente — `POLICY_STANDING` ya está prendida con `REJECT`. Andá directo
  al §2 de este documento.
- Para probar el equivalente del §9.1 (`STANDBY`), el referente tiene que cambiar el selector primero
  (ver §2).

> Nota al margen: esto explica retroactivamente el bug del tenant equivocado que arreglamos — cuando
> `POL-CEL-2026-905` (en mora) se denunció por error bajo el esquema de BBVA, `POLICY_STANDING` ni
> siquiera existe ahí, así que el gate de mora nunca se evaluó y el alta pasó igual. Si hubiese ido al
> esquema correcto de entrada, el 422 lo habría frenado ahí mismo.

### 0.3 · Diferencia real con BBVA #2 — sin `COVERAGE_EXCLUSION` configurada

Provincia tenía tres filas con `rule_type = 'COVERAGE_INCLUSION'` (ids 3, 4, 5) — **no era un valor
que el motor reconociera** (`RuleType` en `common-lib` solo tiene `COVERAGE_EXCLUSION`; `grep` sobre
`rules-service`/`cases-service` confirma que nada consulta `COVERAGE_INCLUSION`, y tampoco aparece en
ningún script de `db/` — no eran reproducibles desde un reseed limpio, puramente datos sueltos de un
diseño de whitelist anterior al que se usa hoy). **Borradas el 17/08** (`DELETE FROM
arbiter_provincia.insurer_rule WHERE rule_type = 'COVERAGE_INCLUSION'`), no hacía falta tocar ningún
script.

**Consecuencia:** el caso "hechos generadores recortados por cobertura" (§11 del documento original,
segunda parte) **no tiene equivalente en Provincia todavía** — ninguna cobertura excluye nada, así
que el selector "¿Qué te pasó?" muestra todos los hechos generadores del ramo sin recortar, para
cualquier póliza de Provincia. Si querés probarlo, primero hay que crear una `COVERAGE_EXCLUSION` de
verdad desde el panel de reglas (solapa Coberturas → cobertura → exclusiones) — no lo armé acá porque
es una decisión de qué excluir que le corresponde al referente, no algo que se deba scriptear.

---

## 1 · Equivalencia de casos

| Caso original (BBVA) | Qué cambia para Provincia |
|---|---|
| §1 — Punto de partida | Mismas dos queries, cambiando `arbiter_bbva` → `arbiter_provincia` y los ids esperados (**6,7,8,9** por cobertura; **14** vigencia). `POLICY_STANDING` (**15**) sí aparece acá — ver §0.2. |
| §2 — Preparar la aseguradora | Login `referente.provincia.arbiter@gmail.com`. Cobertura "Robo de celular": mismo ajuste de plazo de denuncia a 30 días. Fast Track: mismo setup, ver §0.1. |
| §3 — Denuncia con policial tardía | Login `asegurado.arbiter@gmail.com` (Martina), póliza **`POL-CEL-2026-777`**. Mismas fechas relativas ("hace 10 días" / "hace 5 días") — la póliza está vigente desde 01/04/2026, muy anterior a cualquier fecha relativa razonable, así que no hace falta ajustar nada. |
| §4 — Cambiar umbral (criterio 2) | Igual, pero el `rule_id` de la query de auditoría es **8**, no 7: `WHERE h.rule_id = 8`. |
| §5 — Apagar una regla (criterio 4) | Igual, `WHERE id = 8`. |
| §6 — Gate de alta (vigencia/carencia/fechas) | Misma póliza `POL-CEL-2026-777`. Vigencia arranca **01/04/2026**: usar una fecha de hecho anterior a esa (p. ej. **15/03/2026**) para 6.1, y una fecha dentro de la carencia (30 días desde el alta → cubre desde **01/05/2026**) como **10/04/2026** para 6.2. |
| §8 — Corte degradado (rules-service caído) | Igual, sin cambios — no depende del tenant. |
| §9.1 — mora `STANDBY` | Referente: Hard Stop → Mora ya está prendida, cambiar el selector de "Rechazar en el alta" a **"Dejar en standby"**. Guardar. Asegurado `asegurado2.arbiter@gmail.com` (Julián), póliza **`POL-CEL-2026-905`**. |
| §9.2 — mora `REJECT` | **Ya está en este estado ahora mismo** (ver §0.2) — no hace falta ningún paso de setup, andá directo a denunciar con Julián / `POL-CEL-2026-905` y confirmar el 422. |
| §11 (precheck, mora) | Mismo endpoint `POST /cases/eligibility`, mismo comportamiento — usar Julián / `POL-CEL-2026-905` en vez de "Diego Sosa". |
| §11 (precheck, hechos generadores recortados) | **No tiene equivalente hoy** — ver §0.3. |

---

## 2 · Orden sugerido para arrancar

1. **Primero lo más rápido**: login como Julián (`asegurado2.arbiter@gmail.com`), nueva denuncia con
   `POL-CEL-2026-905`. Con la mora ya en `REJECT`, tendría que bloquear "Siguiente" en el paso 2 (o
   dar 422 si se fuerza por API) sin tocar nada del lado del referente — es la prueba más rápida de
   que el fix del tenant funciona de punta a punta con datos reales de mora.
2. Después, el camino feliz completo (§1 a §6 de la tabla de arriba) con Martina / `POL-CEL-2026-777`.
3. Volver a la mora para probar `STANDBY` (§9.1 de la tabla).
