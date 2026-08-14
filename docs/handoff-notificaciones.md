# Handoff — Notificaciones automáticas al asegurado

Historia "Envío de notificaciones automáticas al asegurado" (Fede, sprint 8).
Rama `feature/notificaciones`. Estado: **backend + frontend hechos y verificados en vivo** contra
Railway con Docker.

## Qué quedó andando

- El asegurado recibe mail (SendGrid) + notificación en la campana cuando su siniestro entra a
  `PENDING_CLASSIFICATION` (alta), `AWAITING_DOCUMENTATION`, `APPROVED` o `REJECTED`.
- `GET /api/v1/notifications`, `/unread-count`, `POST /read-all`, `POST /{id}/read`. Sin chequeo de
  rol: la cuenta sale del token y toda query filtra por ella.
- El envío es best-effort: si falla el mail, la transición del expediente igual se aplica.

## Pendiente

### 1. Falta `created_at` en `notification` — bloqueado por Aylén

La card pide "fecha" en el registro. Hoy se ordena por `id` y se muestra `sent_at`, que es `NULL` si
el mail nunca salió. Agregar la columna toca `db/init-multitenant.sql`, que es territorio de Aylén.

### 2. `notification.type` tiene tres convenciones — coordinar con Valentina

| Origen | Valores |
|---|---|
| `db/init-multitenant.sql` | `CASE_STATUS_CHANGED`, `DOCUMENTATION_REQUIRED` |
| `db/seed-demo-extra.sql` | `CAMBIO_ESTADO` |
| `CaseNotificationService` | el `CaseStatus` (`APPROVED`, `REJECTED`, …) |

**Efecto visible hoy:** las notificaciones semilla caen al título genérico del panel ("Novedades de
tu siniestro") porque su `type` no está en el mapa del front.

La card de Valentina (alertas internas por vencimiento de plazos) escribe en **la misma tabla**.
Hay que acordar la convención antes de que las dos historias convivan.

### 3. Notificaciones para el analista — historia nueva, no es ésta

Hoy la campana de los roles internos siempre está vacía: esta historia solo genera avisos al
asegurado. Las del analista serían de otra naturaleza ("el asegurado cargó la documentación",
"nuevo expediente asignado"), no los mismos textos. Pisa el punto 2: coordinar con Valentina.

### 4. No hay forma de que el analista pida documentación

No existe la transición `PENDING_ANALYST_REVIEW → AWAITING_DOCUMENTATION`: ese estado solo lo
setea `ClassificationServiceClient`. La solapa "Conversación" del detalle es un `app-empty-state`.
Decisión de Fede: **no se toca la máquina de estados** por ahora.

## Datos de prueba tocados en Railway

- `arbiter_bbva.insured.email` y `arbiter_provincia.insured.email` de Martina (dni 42.987.654)
  apuntan a `mocciafederico@hotmail.com` para que los mails de prueba lleguen. Revertir si molesta.
- Quedan 5 notificaciones semilla con fecha 2024/2025, o sea anteriores a que Arbiter existiera.
  Fede decidió no borrarlas por ahora. Si molestan para la defensa, se re-fechan o se borran.

## Bugs encontrados y arreglados en esta historia

1. **Faltaba `sendgrid-java` en el pom de cases-service.** Está `optional` en common-lib, así que no
   se hereda: compila y arranca, explota al mandar el primer mail.
2. **`catch (Exception)` no alcanzaba.** `NoClassDefFoundError` es un `Error`, no una `Exception`: el
   expediente quedaba aprobado y con historial escrito, pero el analista recibía un 500. Ahora es
   `catch (Exception | LinkageError)`.
3. **Faltaba `/api/v1/notifications` en `proxy.conf.json`.** Sin la entrada, el path caía al
   `index.html` del SPA con 200 y el contador mostraba 0 en silencio.
4. **Los links del panel iban a `/cases/:id`**, que está guardado para analista/referente: al
   asegurado el guard lo rebotaba a `/portal`. Ahora la ruta se resuelve por rol.
5. **El seed mandaba notificaciones de asegurado al analista.** `seed-demo-extra.sql` hardcodeaba
   `recipient_id` 1 y 2 como si fueran dos asegurados, pero el user 2 es `analista.arbiter@gmail.com`.
   Tres filas le aparecían en la campana con textos tipo "Tu siniestro fue rechazado", y el dueño
   real no las veía. El código estaba bien —el scoping por token funciona—, el dato estaba mal.
   Corregido en Railway y en el seed, que ahora **deriva el destinatario del asegurado del caso** en
   vez de escribir un id a mano.
6. **`NotificationsService.clear()` era código muerto**, nunca se llamaba desde el logout.

## Vocabulario: al asegurado se le dice "siniestro"

El portal ya iba mayoritariamente por ahí ("Mis siniestros", "siniestro #1"), pero `estado.ts`
mezclaba *"Tu **expediente** está en análisis"* con *"Tu **siniestro** fue aprobado"* en el mismo
`Record`. Se unificaron los textos visibles del asegurado; identificadores, rutas y
`mis-expedientes.component` quedan como están (son nombres de dominio).

`App.caseNoun()` resuelve la palabra por rol, porque el panel de la campana es compartido.

**La contra, por si se revisa:** según el vocabulario de CLAUDE.md, "expediente" es *más* preciso —
lo que se analiza es el caso administrativo, no el hecho. Se priorizó la palabra que conoce el
asegurado, que nunca ve esa maquinaria.
