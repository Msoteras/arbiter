# Pendientes y verificación — cambios de fin de agosto / 1 de septiembre

Rama `pruebas-y-fixes-septiembre`. Cubre cuatro tandas de trabajo hechas sobre el doc de gaps
(`docs/gap-dominio-bbva.md`, que tiene el detalle de cada una):

1. Consistencia interna del estado `LAPSED` (caducidad por inacción).
2. Reapertura de expedientes cerrados ("rehabilitación") + aviso al asegurado.
3. Una póliza con **varias** coberturas (`policy_coverage`).
4. Exclusiones de cobertura del seed, que estaban cargadas con un `rule_type` inexistente.

Este archivo es lo que **falta**, no lo que se hizo. Se borra cuando se vacíe.

---

## 1 · Migraciones a correr antes de desplegar

Los servicios corren con `ddl-auto=validate`, así que el esquema tiene que estar migrado **antes**
de que suba el código. En este orden:

```bash
psql "$DATABASE_URL" -f db/migrations/2026-08-31-caducidad.sql
```
```bash
psql "$DATABASE_URL" -f db/migrations/2026-09-01-policy-coverage.sql
```
```bash
psql "$DATABASE_URL" -f db/migrations/2026-09-01-coverage-exclusion-viva.sql
```

Las tres son idempotentes. La segunda es la única que dropea columnas
(`policy.coverage_id`, `policy.sum_insured`), y lo hace después de backfillear.

**Verificación posterior** (las consultas completas están comentadas al pie de cada archivo):

- Cada póliza de celulares tiene que traer **2** filas en `policy_coverage`, no 1.
- Ninguna póliza puede quedar **sin** filas en `policy_coverage`: si queda alguna, el alta de
  denuncia contra ella falla con 422.
- `arbiter_common.case_status` tiene que tener la fila 8, `LAPSED`.
- Cada cobertura tiene que tener su fila `COVERAGE_EXCLUSION` activa, y **ninguna**
  `COVERAGE_INCLUSION` activa.

---

## 2 · Decisión abierta: qué se hace con los expedientes ya creados

**Qué se sabe.** El alta quedó arreglada de acá en adelante, y las pólizas también. Lo que no se
tocó son los expedientes que ya existen: `cases.coverage_id` sigue apuntando a la cobertura que
eligió el modelo viejo, y su `policy_snapshot` tiene congelada la suma asegurada de esa cobertura.
En Railway son los tres expedientes de Hurto colgados de Robo de celular.

**Qué falta decidir.** No es un `UPDATE`: cambiar la cobertura de un expediente cambia los
parámetros con los que se evaluaron sus reglas duras (carencia, plazo de denuncia, tope de eventos,
franquicia, inclusión de cobertura), así que hay que **reclasificarlos**, y eso reescribe
resultados que un analista puede haber mirado ya. Tres caminos:

- **Reclasificar los afectados.** Queda todo consistente; se pierde la clasificación anterior, que
  igual queda en el log inmutable.
- **Corregir solo `coverage_id`** y dejar la clasificación vieja. El expediente apunta bien pero su
  resultado se calculó con otros parámetros: inconsistente, aunque auditable.
- **Dejarlos.** Arrastran el dato viejo; la mitigación de la solapa de trazabilidad ya aclara de
  qué cobertura es la suma.

**Bloquea.** Nada del despliegue. Pero conviene cerrarlo antes de mostrar esos expedientes en la
defensa, porque el número que muestran no es el de su hecho generador.

La consulta para listar los afectados está al pie de
`db/migrations/2026-09-01-policy-coverage.sql` (verificación 3).

---

## 3 · Decisiones que tomé yo sobre la marcha y conviene ratificar

Ninguna bloquea, pero las anoto porque no fueron consultadas antes de codear:

- **La cobertura del expediente se elige por el hecho generador denunciado**, no la elige el
  asegurado en el wizard. El vínculo sale de la regla `COVERAGE_EXCLUSION` que ya administra el
  referente.
- **`arbiter_*.policy` pasó a tener varias coberturas** (tabla `policy_coverage`), en vez de
  resolverlo en el alta sin tocar el modelo.
- **La reapertura no crea un estado `REHABILITADO`**: devuelve el expediente a
  `PENDING_ANALYST_REVIEW` y la traza queda en `case_status_history`.
- **Reabrir reinicia el plazo del art. 56** a 30 días desde cero.
- **Una cobertura sin exclusiones sigue cubriendo todo su ramo** (fail-open), con aviso al
  referente. Descartado invertirlo — ya registrado como decisión cerrada en el doc de gaps.

---

## 4 · Cobertura de tests que falta

Ninguno de estos rompe nada hoy; es deuda:

- `LapseSweepScheduler` no tiene test propio (sí lo tiene la transición a `LAPSED`).
- El gate de prescripción de `ClassificationOrchestrator` no tiene test.
- `CaseServiceImpl.reopenCase` y el endpoint `POST /cases/{id}/reopen` no tienen test de service ni
  de controller (sí lo tiene la máquina de estados y el notificador).
- `PolicySynchronizer` no tiene test contra base real: los suyos son unitarios con el repositorio
  mockeado, así que el backfill multi-cobertura no se ejercita contra Postgres.

---

## 5 · Deuda menor detectada de paso

- `aseguradora.siniestro_historico` no tiene `cobertura_id`, solo `causa`. El acumulado del
  agotamiento por monto suma los siniestros liquidados **de la póliza**, no los de la cobertura, así
  que es más conservador de lo que debería.
- `db/init.sql` es el script single-tenant anterior a la migración multi-esquema. No lo referencia
  nada y quedó desactualizado (tiene `policy.insured_amount`). Candidato a borrar.
- `DeadlineSweepScheduler` tiene un comentario en castellano; la convención del proyecto es
  comentarios en inglés. Es previo a estos cambios.
