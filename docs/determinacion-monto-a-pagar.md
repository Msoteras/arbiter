# Determinación del monto a pagar — estado, pruebas y qué sigue

Al 06/09/2026. **Los cinco bloques están implementados, commiteados y desplegados**, con sus
migraciones aplicadas. Falta probar en la app el bloque 5 y un camino del bloque 2 (ver §3).

El analista no solo aprueba o rechaza: también determina **cuánto se paga**. Es un paso del
procedimiento real de la compañía (manual NSIN001 §5.2.1.2, "Liquidación del Siniestro") que el
expediente no tenía — llegaba a APROBADO sin decir un monto.

La fórmula no la inventamos, está en los manuales de producto. Son dos, según qué le pasó al bien:

```
PÉRDIDA TOTAL — el bien no está
techo                  = suma asegurada, o el menor entre ésa y el valor de reposición
tope del evento        = techo × % del evento          (2.º evento del año → 50%)
− franquicia           = suma asegurada × franquicia%
− cuotas a vencer      = cuotas que restan × importe de cuota
− deuda vencida        = saldo impago del contrato
= monto a pagar        (nunca negativo)

REPARACIÓN — el bien quedó dañado
techo                  = presupuesto acreditado, tope la suma asegurada
tope del evento        = techo × % del evento
− franquicia           = suma asegurada × franquicia%
− deuda vencida        = saldo impago del contrato
= monto a pagar        (nunca negativo)
```

**Lo que separa a las dos son las cuotas a vencer.** Se descuentan porque la pérdida total extingue
el contrato y el premio que resta del año se cobra de la indemnización; después de una reparación el
contrato sigue vivo y el asegurado lo sigue pagando mes a mes.

Fuentes, en orden: manual de Celulares ("La suma asegurada menos la franquicia menos las cuotas
pendientes de pago"); art. 7 de la cláusula 340 (Bases de Indemnización) para el techo como *el
menor* de los dos; condiciones particulares de Tecnología Portátil para el segundo evento al 50%;
art. 5 de la cláusula 102 para la deducción de la deuda vencida.

> **La franquicia es el 10% de la SUMA ASEGURADA, no del monto que se paga.** Es la lectura
> literal de las dos pólizas y del ejemplo del manual ($300.000 asegurados → $30.000 de
> franquicia). Importa en un segundo evento, donde el techo baja a la mitad y la franquicia no.

---

## 1 · Migraciones

| Migración | Estado |
|---|---|
| `db/migrations/2026-09-01-determinacion-monto-a-pagar.sql` | ✅ Aplicada el 02/09 |
| `db/migrations/2026-09-02-atribuciones-de-liquidacion.sql` | ✅ Aplicada el 03/09 |
| `db/migrations/2026-09-06-formula-de-reparacion.sql` | ✅ Aplicada el 06/09 |
| `db/migrations/2026-09-06-monto-del-peritaje.sql` | ✅ Aplicada el 06/09 |

**No queda ninguna pendiente.** Para una base nueva, el trío `reset → init → seed` ya las incluye;
para una que tenga datos:

```bash
python scripts/db-railway-migrate.py db/migrations/<archivo>.sql
```

> Al mergear develop el 03/09 aparecieron **cuatro migraciones de otras historias que nunca se
> habían corrido** (`caducidad`, `coverage-exclusion-viva`, `historico-cobertura`,
> `policy-coverage`). `cases-service` no levantaba por eso. Si al levantar el stack falla con
> `missing table`, buscá en `db/migrations/` lo que no se aplicó.

**El orden importa.** Los servicios corren con `ddl-auto: validate`: si se despliega el código
antes de correr su migración, `cases-service` no levanta. Al revés no rompe nada — las cuatro son
aditivas, y una base migrada con código viejo funciona igual.

Las cuatro se probaron reproduciendo la secuencia exacta de Railway (esquema viejo → migración 1 →
2 → 3 → 4) sobre un Postgres descartable, verificando que una base migrada queda **idéntica** a una
recién creada.

### Ojo con el consumo de datos

El stack local apunta a la base de Railway. Con los cinco servicios prendidos consume del orden de
**2 GB en 5 minutos** (pollers y schedulers contra una base remota). Con datos móviles, levantar
solo lo necesario y apagar con `docker compose stop`.

---

## 2 · Qué probar

Los topes sembrados son **Celulares $500.000** y **Tecnología Portátil $50.000**. Con eso:

- Cualquier robo de celular de la demo (sumas de $200.000 a $1.500.000) da un monto **por encima**
  del tope → pasa por el referente.
- El expediente resuelto de Provincia (case 1, Tecnología, $29.000) queda **por debajo** → lo firma
  el analista solo.

### Referente — configuración

- [ ] En **Reglas → Coberturas**, los cuatro parámetros nuevos guardan y vuelven bien: techo
      indemnizable, % del 2.º evento, descontar cuotas a vencer, descontar deuda vencida.
- [ ] En **Reglas → Atribuciones de liquidación** se ven los dos ramos con su tope.
- [ ] Cambiar un tope y recargar: quedó guardado.
- [ ] Vaciar el campo saca el tope y la fila pasa a decir **"Sin tope"** (no `$0`).

### Analista — determinar el monto

- [ ] Aprobar un expediente abre la hoja de liquidación con el desglose línea por línea, cada
      importe con su explicación ("10% de la suma asegurada", "7 cuotas × $26.000").
- [ ] **La cuenta da bien.** Contrastar contra el ejemplo del manual: $300.000 de suma asegurada,
      10% de franquicia y 7 cuotas de $5.000 → **$235.000**.
- [ ] Cargar un valor de reposición y tocar **Recalcular**: en una cobertura que liquida por el
      menor de los dos, el techo baja; en una que liquida por suma asegurada, no cambia el monto.
- [ ] Cambiar el monto a mano exige justificar el ajuste (el botón queda deshabilitado sin motivo).
- [ ] Un monto **mayor a la suma asegurada** se rechaza aunque se justifique (es el límite
      contractual, art. 3).
- [ ] **Rechazar** no pide monto.
- [ ] Un expediente ya aprobado muestra el monto, y si hubo ajuste, sobre qué se ajustó y por qué.
- [ ] Los avisos aparecen cuando falta un dato (sin valor de reposición acreditado, sin importe de
      cuota en la póliza) y **no** bloquean la firma.

### Analista + referente — atribuciones

- [ ] Un monto **por debajo** del tope resuelve el expediente en el acto, como antes.
- [ ] Un monto **por encima** avisa en el modal *antes* de confirmar: "supera tu atribución".
- [ ] Al confirmarlo, el expediente **queda en revisión del analista** (no pasa a APROBADO) y
      muestra "Esperando autorización del referente".
- [ ] En la base, `case_classification` **no** tiene fila todavía para ese expediente.
- [ ] El referente lo ve en **Autorizaciones** con el monto, el tope, cuánto lo excede y hace
      cuánto espera.
- [ ] **Autorizar** → el expediente pasa a APROBADO y sale el mail al asegurado **con el monto**.
- [ ] La decisión queda atribuida al **analista** que la tomó, no al referente que la firmó.
- [ ] **Devolver** sin motivo se rechaza.
- [ ] **Devolver** con motivo → el analista ve el motivo en el expediente y puede volver a
      determinar el monto; al reconfirmar, el motivo desaparece.
- [ ] Autorizar algo que ya no está esperando firma da error, no una doble aprobación.

### Reparación (bloque 3)

Ninguna cobertura de BBVA repara; la de daño está en **Provincia**. Para probarlo con un
expediente de BBVA hay que cambiarle la fórmula a su cobertura desde Reglas → Coberturas, y
devolverla después.

- [ ] Con la cobertura en **reparación**, el techo indemnizable y el descuento de cuotas
      desaparecen de la pantalla del referente: dejan de tener sentido.
- [ ] En el modal del analista el campo pasa a ser **"Presupuesto de reparación"** con asterisco.
- [ ] **Sin presupuesto, el monto es $0** y lo dice — no cae a la suma asegurada.
- [ ] La línea de **"Cuotas a vencer" no aparece**: la reparación no extingue la póliza. Es la
      diferencia real entre las dos fórmulas.
- [ ] Con presupuesto: se paga el presupuesto menos la franquicia, con tope en la suma asegurada.

### Sugerencia desde la documentación (bloque 4)

Necesita un expediente con un documento ya analizado que traiga importe. En Railway los tienen los
expedientes **37 y 38** (comprobante de compra, $589.999).

- [ ] Aparece la sugerencia con la **procedencia**: "En «Comprobante de compra» figura $X".
- [ ] **Usar** la carga y recalcula. Ojo: la base está en Railway, tarda un segundo.
- [ ] La sugerencia **desaparece** una vez que hay un valor cargado.
- [ ] En reparación pide el **presupuesto** y no ofrece el comprobante de compra, ni al revés.
- [ ] Donde el campo no aplica (pérdida total por suma asegurada) **no sugiere nada**.

### Monto del peritaje (bloque 5) — sin probar

- [ ] Al cargar el informe, el formulario pide **"Monto indemnizable que determinó el perito"**,
      opcional.
- [ ] Ese monto **le gana** al del presupuesto en la sugerencia, y la procedencia dice
      "Informe de peritaje".
- [ ] Dejarlo vacío no rompe nada ni se guarda como cero.

### Asegurado — que no vea nada de esto

- [ ] Mientras la liquidación espera autorización, el portal del asegurado **no** muestra ningún
      estado nuevo: el siniestro sigue "en análisis".
- [ ] El mail de aprobación llega una sola vez, con el monto.

---

## 3 · Cosas que ya sé que faltan

No son bugs, son decisiones de alcance. Vale tenerlas a la vista antes de probar para no
confundirlas con fallas.

- **La bandeja del analista no marca los expedientes esperando firma.** Se entera al abrirlos.
  Ponerlo en el listado obliga a atravesar `CaseResponse` por cinco sobrecargas del mapper.
- **La bandeja de autorizaciones arranca vacía** en una base recién sembrada: se llena aprobando un
  expediente por encima del tope. En Railway quedaron dos liquidaciones de prueba (expedientes 11 y
  29) cargadas a mano para poder ver la pantalla — **borralas cuando no las necesites**.
- **El monto autorizado no vuelve a la aseguradora.** Queda en Arbiter: la integración es de una
  sola mano (leemos snapshots, no escribimos). Abrir escritura es una decisión de arquitectura
  pendiente.
- **Falta probar en la app**: el bloque 5 entero, y el camino de **devolver** del bloque 2 (se
  verificó que sin motivo no deja, pero no se completó una devolución).

### Una pregunta para el equipo

Con franquicia del 10% sobre una suma asegurada de $1.300.000, **toda reparación de menos de
$130.000 paga cero** — una pantalla rota entra ahí de lleno. Está implementado al pie de la letra
de la póliza (la franquicia es un porcentaje de la suma asegurada, no del daño), pero conviene
confirmar si en la práctica las coberturas de daño la calculan sobre el presupuesto. Si es así, el
cambio es chico.

---

## 4 · Qué sigue

Los cinco bloques están. Lo que quedó abierto es lo de §3: probar el bloque 5 y la devolución,
la marca en la bandeja del analista, y la pregunta de la franquicia en reparaciones.

Más adelante, si se quiere:

- **Que el modelo lea el informe pericial.** Hoy el monto del perito lo tipea el analista. Se
  descartó extraerlo porque un informe pericial no tiene formato —cada estudio escribe el suyo— y
  la extracción sería menos confiable justo donde el número tiene más autoridad. Se puede sumar
  después como sugerencia sobre ese campo, igual que el bloque 4.
- **Devolverle el monto a la aseguradora**, cuando se decida abrir la escritura.

---

## 5 · Dónde está cada cosa

| Qué | Dónde |
|---|---|
| Las dos fórmulas | `cases-service/.../services/SettlementCalculator.java` |
| Propuesta, confirmación, autorización y devolución | `cases-service/.../services/SettlementService.java` |
| El tope por ramo | `cases-service/.../services/SettlementAuthorityService.java` |
| Retener la decisión hasta que se autorice | `CaseServiceImpl.recordAnalystDecision` / `authorizeSettlement` |
| El monto en el mail | `CaseNotificationService.approvedAmountLine` |
| La sugerencia desde documentos y peritaje | `SettlementService.suggestionFor` |
| El monto que carga el perito | `ExpertAssessmentService.receiveReport` |
| Parámetros por cobertura (referente) | `arbiter-frontend/.../admin/reglas/` |
| Topes por ramo (referente) | `arbiter-frontend/.../admin/atribuciones-config/` |
| Bandeja de autorizaciones (referente) | `arbiter-frontend/.../admin/autorizaciones/` |
| Hoja de liquidación (analista) | `arbiter-frontend/.../expediente-detail/` |
| El ejemplo del manual como test | `SettlementCalculatorTest.reproducesTheWorkedExampleFromTheCelularesManual` |

Endpoints nuevos, todos en la colección `docs/postman/Arbiter_Cases_EndToEnd.postman_collection.json`:

```
GET  /api/v1/cases/{id}/settlement[?replacementValue=]   propuesta o liquidación (analista/referente)
POST /api/v1/cases/{id}/decision                          ahora lleva `settlement` al aprobar
GET  /api/v1/settlement-authorities                       topes por ramo (referente)
PUT  /api/v1/settlement-authorities/{branchId}            fijar o sacar el tope (referente)
GET  /api/v1/cases/settlements/pending-authorization      cola de firma (referente)
POST /api/v1/cases/{id}/settlement/authorize              autorizar (referente)
POST /api/v1/cases/{id}/settlement/return                 devolver con motivo (referente)
POST /api/v1/cases/{id}/expert-assessment/report          ahora acepta `indemnifiableAmount`
```
