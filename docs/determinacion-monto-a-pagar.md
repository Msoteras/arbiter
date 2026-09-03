# Determinación del monto a pagar — estado, pruebas y qué sigue

Al 02/09/2026. Los bloques 1 y 2 están implementados y **sin commitear**; ninguno se probó en la
app corriendo.

El analista no solo aprueba o rechaza: también determina **cuánto se paga**. Es un paso del
procedimiento real de la compañía (manual NSIN001 §5.2.1.2, "Liquidación del Siniestro") que el
expediente no tenía — llegaba a APROBADO sin decir un monto.

La fórmula no la inventamos, está en los manuales de producto:

```
techo                  = suma asegurada, o el menor entre ésa y el valor de reposición
tope del evento        = techo × % del evento          (2.º evento del año → 50%)
− franquicia           = suma asegurada × franquicia%
− cuotas a vencer      = cuotas que restan × importe de cuota
− deuda vencida        = saldo impago del contrato
= monto a pagar        (nunca negativo)
```

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
| `db/migrations/2026-09-01-determinacion-monto-a-pagar.sql` | ✅ **Aplicada a Railway** el 02/09 |
| `db/migrations/2026-09-02-atribuciones-de-liquidacion.sql` | ❌ **Falta correr** |

```bash
python scripts/db-railway-migrate.py db/migrations/2026-09-02-atribuciones-de-liquidacion.sql
```

**El orden importa.** Los servicios corren con `ddl-auto: validate`: si se despliega el código del
bloque 2 antes de correr su migración, `cases-service` no levanta. Al revés no rompe nada — las
dos migraciones son aditivas, y una base migrada con código viejo funciona igual.

Verificación después de correrla:

```sql
SELECT table_schema FROM information_schema.tables WHERE table_name = 'settlement_authority';
SELECT branch_id, max_amount FROM arbiter_bbva.settlement_authority;
SELECT case_id, status, authority_limit FROM arbiter_provincia.case_settlement;
```

Las dos ya se probaron reproduciendo la secuencia exacta de Railway (esquema viejo → migración 1 →
migración 2) sobre un Postgres descartable, y se verificó que una base migrada queda **idéntica** a
una recién creada.

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
- **La bandeja de autorizaciones arranca vacía.** No sembramos ninguna liquidación pendiente: se
  crea aprobando un expediente por encima del tope, que es justo lo que hay que mostrar.
- **La liquidación sembrada del case 1 de Provincia dice `TOTAL_LOSS` y es un daño** (rotura de
  pantalla). Da el número correcto igual, porque con esa cobertura las dos fórmulas coinciden. Se
  reetiqueta cuando entre el bloque 3.
- **El monto autorizado no vuelve a la aseguradora.** Queda en Arbiter: la integración es de una
  sola mano (leemos snapshots, no escribimos). Abrir escritura es una decisión de arquitectura
  pendiente.

---

## 4 · Qué sigue

### Bloque 3 · Segunda fórmula: daño por tentativa de robo

Presupuesto de reparación menos franquicia, **sin** descontar cuotas a vencer: la reparación no
extingue la póliza. La fórmula se elige según el hecho generador, configurado por el referente.
Hoy `case_settlement.formula` solo acepta `TOTAL_LOSS`.

### Bloque 4 · El LLM extrae los importes

El análisis de documentos ya está construido. Se le suma leer importes de facturas, tickets y
presupuestos, y proponerlos como **valor de reposición sugerido** — con el documento de origen a la
vista para que el analista lo verifique de un vistazo. Sugerencia, nunca vinculante.

### Bloque 5 · El peritaje devuelve un monto

El informe pericial hoy trae veredicto; le sumamos monto indemnizable sugerido, que entra como
input a la liquidación cuando el expediente vuelve del perito.

---

## 5 · Dónde está cada cosa

| Qué | Dónde |
|---|---|
| La fórmula | `cases-service/.../services/SettlementCalculator.java` |
| Propuesta, confirmación, autorización y devolución | `cases-service/.../services/SettlementService.java` |
| El tope por ramo | `cases-service/.../services/SettlementAuthorityService.java` |
| Retener la decisión hasta que se autorice | `CaseServiceImpl.recordAnalystDecision` / `authorizeSettlement` |
| El monto en el mail | `CaseNotificationService.approvedAmountLine` |
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
```
