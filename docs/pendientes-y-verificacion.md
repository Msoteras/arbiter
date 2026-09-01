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

### Smoke test manual que queda

`PolicyCoverageTests` ya cubre el núcleo del bug contra Postgres (importación de las dos
coberturas, resolución del hurto contra la suya, y el número que se congela). Lo que **no** puede
cubrir un IT y hay que mirar a mano una vez desplegado:

1. **El selector del wizard.** Entrar como asegurado con una póliza de celulares y confirmar que
   "Hurto" aparece entre los hechos generadores. Antes no aparecía.
2. **El salto a classification.** Que el expediente creado quede con la cobertura de hurto y que la
   clasificación se haya evaluado con los parámetros de esa cobertura (carencia, plazo, franquicia),
   no con los de robo. Se ve en la solapa de trazabilidad.
3. **El aviso al referente.** Panel de reglas → solapa Coberturas: una cobertura sin exclusiones
   tiene que mostrar el badge "Cubre todo el ramo" con la tarjeta cerrada.
4. **Reapertura.** Reabrir un expediente cerrado: vuelve a revisión, le llega el mail al asegurado
   y el plazo arranca de cero.

---

## 2 · Expedientes ya creados con la cobertura vieja — decidido: se dejan

**Qué pasó.** Los expedientes creados antes del arreglo tienen `cases.coverage_id` apuntando a la
cobertura que elegía el modelo viejo (siempre la primera de la póliza), y su `policy_snapshot` tiene
congelada la suma asegurada de esa cobertura. En Railway son los tres expedientes de Hurto colgados
de Robo de celular. El arreglo corrige el momento del alta; a estos no los vuelve a leer nadie.

**Decidido (01/09/2026): se dejan como están.** La base es de prueba y, en el peor caso, se levanta
de cero con `reset → init → seed`, que ya nace con el modelo nuevo. Corregirlos no valía el costo:
no es un `UPDATE`, porque la cobertura lleva los parámetros con los que se evaluaron las reglas
duras del expediente (carencia, plazo de denuncia, tope de eventos, franquicia, exclusiones), así
que dejar el dato consistente exige reclasificar y eso reescribe recomendaciones ya emitidas.

**Si en algún momento hay que hacerlo sobre datos que importen**, el camino limpio existe desde que
se sumó la reapertura: reabrir con motivo → reclasificar → volver a decidir. Queda la traza completa
en `case_status_history` de por qué se tocó el expediente. La consulta para listar los afectados
está al pie de `db/migrations/2026-09-01-policy-coverage.sql` (verificación 3).

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
- ~~`PolicySynchronizer` no tiene test contra base real.~~ Cubierto por `PolicyCoverageTests`
  (IT): importa una póliza con dos coberturas, resuelve un hurto contra la de hurto con su propia
  suma asegurada, verifica que el wizard ofrezca los dos hechos y que la búsqueda de póliza por
  número siga funcionando contra Hibernate real. Ese último caso existe por el `@EntityGraph` que
  quedó apuntando a un atributo borrado: invisible con el repositorio mockeado, y una excepción en
  cada alta de denuncia contra la base.

---

## 5 · Deuda menor detectada de paso

- `aseguradora.siniestro_historico` no tiene `cobertura_id`, solo `causa`. El acumulado del
  agotamiento por monto suma los siniestros liquidados **de la póliza**, no los de la cobertura, así
  que es más conservador de lo que debería.
- `db/init.sql` es el script single-tenant anterior a la migración multi-esquema. No lo referencia
  nada y quedó desactualizado (tiene `policy.insured_amount`). Candidato a borrar.
- `DeadlineSweepScheduler` tiene un comentario en castellano; la convención del proyecto es
  comentarios en inglés. Es previo a estos cambios.
