# Pendientes y verificación — cambios de fin de agosto / 1 de septiembre

Rama `pruebas-y-fixes-septiembre`. Cubre cuatro tandas de trabajo hechas sobre el doc de gaps contra
el procedimiento real de BBVA (`docs/gap-dominio-bbva.md`, borrado el 06/09 al quedar 17 de sus 21
puntos resueltos — el detalle de cada tanda está en los commits y en las migraciones):

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
```bash
psql "$DATABASE_URL" -f db/migrations/2026-09-01-historico-cobertura.sql
```
```bash
psql "$DATABASE_URL" -f db/migrations/2026-09-05-criterios-fast-track.sql
```

Las cinco son idempotentes. La segunda es la única que dropea columnas
(`policy.coverage_id`, `policy.sum_insured`), y lo hace después de backfillear.

**Verificación posterior** (las consultas completas están comentadas al pie de cada archivo):

- Cada póliza de celulares tiene que traer **2** filas en `policy_coverage`, no 1 — y **3** las
  Premium de BBVA, que desde la quinta migración también cubren daño accidental.
- Ninguna póliza puede quedar **sin** filas en `policy_coverage`: si queda alguna, el alta de
  denuncia contra ella falla con 422.
- `arbiter_common.case_status` tiene que tener la fila 8, `LAPSED`.
- Cada cobertura tiene que tener su fila `COVERAGE_EXCLUSION` activa, y **ninguna**
  `COVERAGE_INCLUSION` activa.
- Los siniestros del histórico tienen que quedar imputados a una cobertura. Los que queden en NULL
  son los que la regla de agotamiento va a saltear: revisar que sean los esperados y no todos.
- Cada cobertura tiene que tener su fila `FAST_TRACK` **con `configuration`**, y no puede quedar
  ninguna `FAST_TRACK` activa sin `coverage_id`: esas son las viejas, que el servicio no encontraba.
- `rule_result` no puede tener más filas con `rule_type` `'FAST_TRACK'` ni `'COVERAGE_INCLUSION'`:
  se reexpresaron como los cinco criterios `FT_*` y como `COVERAGE_EXCLUSION`.
- Todo hecho generador del ramo tiene que ser cubierto por alguna cobertura, y ningún expediente
  puede colgar de una que lo excluya —salvo los casos 8 y 17 de BBVA, que son el escenario de demo
  "hurto no cubierto"— ni apuntar a una cobertura que su póliza no tenga contratada.

**Las sumas aseguradas no necesitan migración.** En la base desplegada las copias locales de tres
coberturas de hurto de BBVA están desalineadas con la BD Aseguradora (y faltan otras dos): eso lo
corrige solo `PolicyResyncScheduler` en su primera corrida, a las 3 AM. Lo que **no** va a corregir
son las tres coberturas de hurto que Provincia tiene del lado de Arbiter y la compañía no: el resync
nunca borra —hay expedientes que podrían colgar de ellas— así que van a aparecer en el warning
*"local coverage(s) the company no longer returns"* todas las noches hasta que alguien decida si las
suma la compañía o se van de Arbiter. En el seed nuevo ya están de los dos lados.

La quinta migración deja **sin tocar** el monto reclamado del caso 11 de BBVA (470.000 sobre una
suma asegurada de 500.000, marcado como Fast Track). Después de migrar, esa contradicción se ve en
pantalla: "Cumple" con un 94% contra un tope de 50%. En el seed nuevo el caso quedó en 240.000; si
se quiere dejar la base desplegada igual que el fixture, al pie del archivo está el UPDATE para
correr a mano. No va en la migración a propósito: un script de datos no reescribe lo que alguien
denunció.

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

## 2 · Deuda que sobrevive

Casi todo lo que este archivo listaba ya se cerró. Lo verificado el 06/09/2026:

- ~~`LapseSweepScheduler` sin test propio.~~ `LapseSweepSchedulerTest` existe.
- ~~Gate de prescripción sin test.~~ `ClassificationOrchestratorPrescriptionTest` existe, con el
  caso límite de un año y un día.
- ~~`reopenCase` y `POST /cases/{id}/reopen` sin test de service ni de controller.~~ Cubiertos en
  `CaseServiceImplTest` y `CaseControllerTest`.
- ~~`PolicySynchronizer` sin test contra base real.~~ Cubierto por `PolicyCoverageTests`.
- ~~`aseguradora.siniestro_historico` sin `cobertura_id`.~~ Resuelto 01/09/2026.
- ~~Comentario en castellano en `DeadlineSweepScheduler`.~~ Ya está en inglés.

- ~~`db/init.sql` quedó desactualizado y no lo referencia nada.~~ **Borrado el 06/09/2026.** Era el
  script single-tenant anterior a la migración multi-esquema: sin un solo `CREATE SCHEMA`, creaba
  sus 35 tablas en el `public` de la base mezclando lo que hoy es `arbiter_common` con lo que hoy es
  por-tenant, y arrancaba con dos docenas de `DROP TABLE IF EXISTS ... CASCADE` sin calificar el
  esquema — con el `search_path` multi-tenant, correrlo por error contra la base desplegada se
  llevaba puestas las tablas del tenant que estuviera primero. El par canónico es
  `db/init-multitenant.sql` + `db/seed-demo.sql`.

Con eso el archivo queda solo como checklist de despliegue: si las cinco migraciones ya corrieron
y el smoke test de arriba está hecho, se borra entero.

Las decisiones que este archivo registraba —los expedientes viejos se dejan como están, la cobertura
se elige por el hecho generador, la reapertura no crea un estado propio, reabrir reinicia el plazo
del art. 56, una cobertura sin exclusiones cubre todo su ramo— quedaron ratificadas por el equipo el
01/09/2026 y viven en el código y en las migraciones. **No reabrir sin un caso nuevo.**
