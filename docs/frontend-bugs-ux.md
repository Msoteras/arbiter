# Bugs y deuda de UX — frontend (relevamiento manual)

**Estado:** documentado, sin fixear. Cada ítem tiene el archivo/línea exacto y la causa verificada en código — no son suposiciones, se leyó el componente correspondiente.

---

## 1. Falta spinner visual en "Ingresar"

**Archivo:** [`login.component.ts`](../arbiter-frontend/src/app/features/auth/login/login.component.ts) + [`login.component.html:44-45`](../arbiter-frontend/src/app/features/auth/login/login.component.html)

El estado `submitting()` sí existe y deshabilita el botón, pero el único feedback visual es el cambio de texto `"Ingresar →"` → `"Ingresando…"`. No hay ningún spinner/loader — en conexiones lentas, el usuario no tiene una señal fuerte de que el click se registró (solo un cambio de texto sutil), lo cual invita al doble-click.

**Fix sugerido:** agregar un spinner inline en `app-button` (o un ícono girando) cuando `submitting()` es true, consistente con el resto del design system.

---

## 2. Mensaje de error genérico y poco útil ("re-trolea" si el back está caído)

**Archivo:** [`login.component.ts:60-70`](../arbiter-frontend/src/app/features/auth/login/login.component.ts)

```ts
private messageFor(err: HttpErrorResponse): string {
  if (err.status === 401) { return err.error?.detail ?? 'Email o contraseña inválidos.'; }
  if (err.status === 423) { return err.error?.detail ?? 'Cuenta bloqueada temporalmente...'; }
  if (err.status === 400) { return 'Completá email y contraseña.'; }
  return 'No se pudo iniciar sesión. Probá de nuevo en unos minutos.';
}
```

El `catch-all` es el mismo mensaje **sea cual sea el problema real**: un 500 del backend, un timeout, `auth-service` caído, sin conexión a internet, un JWT_SECRET mal configurado, etc. Si "se rompió todo" (el backend está abajo), el usuario recibe el mismo texto ambiguo que si hubiese un problema transitorio de un minuto — de ahí la sensación de "troleo": no hay forma de distinguir "reintentá en 2 minutos" de "esto está roto de verdad, avisale a soporte".

**Fix sugerido:** distinguir al menos error de red (sin respuesta del server) de error 5xx, con mensajes distintos, y loguear el detalle real a consola/Sentry para poder diagnosticar sin depender del relato del usuario.

---

## 3. Modal "Eliminar usuario" — sin restricciones por rol

**Archivos:** [`usuarios.component.html:61-100`](../arbiter-frontend/src/app/features/admin/usuarios/usuarios.component.html), [`usuarios.component.ts:129-153`](../arbiter-frontend/src/app/features/admin/usuarios/usuarios.component.ts)

La única restricción hoy es `isSelf(u)` (no podés eliminarte a vos mismo — está resuelto en backend con `CannotDeleteOwnAccountException`). **Cualquier `REFERENTE_ASEGURADORA` puede eliminar a cualquier otro usuario** — analista o referente — con un solo click de confirmación. No hay:
- Distinción de qué puede borrar un referente (¿debería poder borrar a otro referente? ¿o solo analistas?).
- Confirmación reforzada (el comentario del código dice `"wireframe: 'Eliminar pide confirmación destructiva, doble verbo'"` pero la implementación es un solo botón "Sí, eliminar usuario", no un doble verbo real como escribir el nombre del usuario).

Además, el texto del modal dice *"Los expedientes que tenía asignados quedan sin analista"* — pero **no existe el concepto de expediente asignado a un analista** en el modelo de datos (ver punto 7). Es copy que describe una feature que no existe todavía.

**Fix sugerido:** definir la matriz de permisos real (¿quién puede borrar a quién?), reforzar la confirmación, y corregir el copy del modal para no mencionar asignación hasta que exista.

---

## 4. El ícono de notificaciones no tiene funcionalidad

**Archivos:** [`notifications.service.ts`](../arbiter-frontend/src/app/core/notifications/notifications.service.ts), [`app.html:36-52`](../arbiter-frontend/src/app/app.html)

El servicio es un stub completo:

```ts
setUnread(count: number): void { this._unread.set(Math.max(0, count)); }
markAllRead(): void { this._unread.set(0); }
```

`setUnread` **nunca se llama desde ningún lado** — no hay polling, SSE, ni ningún productor real conectado al backend. El contador siempre vale 0. El único handler del ícono en la campana es `(click)="notifications.markAllRead()"` — no abre ningún dropdown, no navega a ningún lado, no lista nada. Es decorativo.

**Fix sugerido:** o se cablea a un endpoint real, o se oculta/deshabilita visualmente hasta que exista el backend, para no prometer una feature inexistente.

---

## 5. El ícono de la pestaña del navegador no es el de Arbiter

**Archivos:** [`index.html:8`](../arbiter-frontend/src/index.html), `public/favicon.svg`, `public/favicon.ico`

Hay un `favicon.svg` custom (círculo azul con una balanza — temática de "arbiter/árbitro"), referenciado correctamente en `index.html` con `<link rel="icon" type="image/svg+xml" href="favicon.svg">`. **Pero también existe `public/favicon.ico`**, sin ningún `<link>` que lo referencie explícitamente, y con fecha **anterior** (30/jun) a la del SVG custom (18/jul) — es casi seguro el ícono genérico que trae el scaffold de Angular CLI por defecto.

Los navegadores piden `/favicon.ico` de forma implícita por convención, independientemente de los `<link>` del HTML — así que es muy probable que Chrome esté priorizando/cacheando ese `.ico` viejo (genérico) en vez del `.svg` nuevo (de Arbiter), sobre todo si la pestaña ya estaba abierta antes de que se agregara el SVG.

**Fix sugerido:** regenerar `favicon.ico` a partir del diseño de Arbiter (o borrarlo si el SVG alcanza para los navegadores objetivo) y probar en una pestaña/perfil sin caché.

---

## 6. "Pendientes" está bloqueado siempre, sin importar si hay pendientes

**Archivo:** [`app.html:11-16`](../arbiter-frontend/src/app/app.html)

```html
<a routerLink="/inbox" routerLinkActive="active"><span class="nav-dot"></span>Bandeja</a>
<span class="nav-item is-disabled" title="Sin datos">
  <span class="nav-dot"></span>Pendientes
</span>
```

No es un bug de estado — es un placeholder **hardcodeado como deshabilitado**. No hay ruta `/pendientes`, no hay componente, no hay lógica condicional: el `title="Sin datos"` no refleja ningún cálculo real, es un tooltip fijo. La pantalla nunca se construyó.

**Fix sugerido:** o se construye la pantalla, o se saca el ítem del nav hasta que exista (mostrar un nav item permanentemente deshabilitado es peor que no mostrarlo).

---

## 7. "¿Cuáles son tus expedientes asignados?" — hoy se muestra TODO

**Archivos:** [`Case.java`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/models/entities/Case.java) (backend), [`bandeja.component.ts`](../arbiter-frontend/src/app/features/expedientes/bandeja/bandeja.component.ts), [`expediente-detail.component.ts:164`](../arbiter-frontend/src/app/features/expedientes/expediente-detail/expediente-detail.component.ts)

Confirmado en backend y frontend: **no existe el concepto de "analista asignado a un expediente"** en absoluto.

- La entidad `Case` no tiene ningún campo de analista asignado.
- `GET /api/v1/cases` no filtra por analista (solo por `status`, `claimCause`, `policyNumber`, `insuredId`, rango de fechas, texto libre y `riskBand`).
- La bandeja del frontend (`bandeja.component.ts`) muestra **todos los expedientes** a cualquier analista logueado, sin distinción de "míos" vs. "de otro".
- En el detalle del expediente hay un campo `{ label: 'Analista asignado', value: null }` — un placeholder de UI que siempre está vacío, para una feature que no existe.

Esto ya estaba documentado como **Gap E** en `docs/siniestros/GAPS-FLUJO.md` (dependía de que `auth-service` estuviera levantado). Auth ya existe hoy (login, JWT, roles), pero **la asignación de expedientes a un analista puntual es una feature aparte que nunca se construyó** — ni el campo en el modelo, ni el endpoint, ni la UI.

**Fix sugerido:** definir si se quiere asignación real (campo `assignedAnalystId` en `Case` + endpoint de asignación + filtro "mis expedientes" en la bandeja) o si el modelo de trabajo es "cualquier analista ve todo" a propósito — y en ese caso, sacar el copy/placeholder que sugiere lo contrario.

---

## 8. Asegurado ve "Denunciado" / "Terminado" — labels mal ubicados

**Archivo:** [`estado.ts:88-111`](../arbiter-frontend/src/app/core/models/estado.ts)

El estado simplificado para el asegurado tiene 3 niveles:

```ts
DENUNCIADO: 'Denunciado',
EN_TRAMITE: 'En trámite',
TERMINADO: 'Terminado',
```

Y el mapeo de estados técnicos → simplificado:

```ts
PENDING_CLASSIFICATION: 'DENUNCIADO',
PENDING_ANALYST_REVIEW: 'EN_TRAMITE',
CLASSIFICATION_FAILED: 'EN_TRAMITE',
AWAITING_DOCUMENTATION: 'EN_TRAMITE',
APPROVED: 'TERMINADO',
REJECTED: 'TERMINADO',
```

El problema concreto (ver también el punto 10): cuando el asegurado sube documentación faltante, el backend **resetea el estado a `PENDING_CLASSIFICATION`** (mismo estado que el día 1). Como ese estado mapea a `DENUNCIADO`, el asegurado ve que su expediente **retrocede** de "En trámite" a "Denunciado" — como si el sistema hubiese olvidado todo el progreso previo. Es un regreso visual que no refleja lo que pasó (subió lo que le pedían, no volvió a fojas cero).

`TERMINADO` en sí no distingue aprobado de rechazado a nivel de la etiqueta corta (aunque sí a nivel de color/tono e `estadoDescripcionAsegurado`), lo cual puede ser intencional (el semáforo de color ya lo distingue) pero vale confirmarlo con el equipo de diseño.

**Fix sugerido:** ver punto 10 — la resolución real está en no volver a `PENDING_CLASSIFICATION` sino a un estado intermedio propio (o mantener el estado simplificado `EN_TRAMITE` aunque el técnico interno cambie).

---

## 9. Paginación rota — el buscador de texto no resetea la página

**Archivo:** [`bandeja.component.ts:83-112, 247`](../arbiter-frontend/src/app/features/expedientes/bandeja/bandeja.component.ts)

Confirmado con precisión: **todos** los filtros de la bandeja (`status`, `claimCause`, `riskBand`, fechas, `clearFilters`, cambio de orden) llaman `this.page.set(0)` al aplicarse — **excepto el buscador de texto libre**:

```ts
protected readonly qDraft = signal('');
private readonly qDebounced = toSignal(
  toObservable(this.qDraft).pipe(debounceTime(350), distinctUntilChanged()),
);
// ...
this.qDraft.set(v);   // línea 247 — el handler del input de búsqueda. NO resetea page.
```

**Repro:** en la bandeja, andá a una página avanzada (ej. página 3 con 20 resultados de 57), después escribí algo en el buscador que matchee solo 2 expedientes. El componente sigue pidiendo `page=2&size=20` al backend, que devuelve una página vacía (no hay 3 páginas de resultados para esa búsqueda) — la tabla se ve vacía o el indicador "Página 3 de 1" queda inconsistente, aunque sí existan resultados en la página 0.

**Fix sugerido:** agregar `this.page.set(0)` al handler de búsqueda, igual que en el resto de los filtros.

---

## 10. El seguimiento del asegurado retrocede después de subir documentación

**Archivos:** [`documentacion.component.ts:59-63`](../arbiter-frontend/src/app/features/portal/documentacion/documentacion.component.ts), [`estado.ts:116-122`](../arbiter-frontend/src/app/core/models/estado.ts), `cases-service` (`CaseServiceImpl.addDocumentsAndReclassify`)

Cadena completa verificada:

1. El asegurado sube los documentos faltantes en `/portal/cases/{id}/documents`.
2. `onUploaded()` redirige a `/portal/cases/{id}` (la pantalla de seguimiento):
   ```ts
   protected onUploaded(): void {
     // El backend ya recibió los documentos y re-encoló la clasificación: volvemos al
     // seguimiento, que va a mostrar el estado actualizado.
     this.router.navigate(['/portal/cases', this.caseId]);
   }
   ```
3. El backend, al recibir documentación adicional, **resetea el estado a `PENDING_CLASSIFICATION`** (confirmado en `CaseServiceImpl`, con el motivo `"documentación adicional subida"`).
4. El seguimiento lee ese estado y usa el título asegurado-facing:
   ```ts
   PENDING_CLASSIFICATION: 'Recibimos tu denuncia',
   ```

Es **el mismo título y el mismo estado simplificado (`DENUNCIADO`)** que ve un asegurado que recién denunció por primera vez. El stepper de progreso (`seguimiento.component.ts`, basado en `estadoSimplificado`) retrocede al primer paso. El asegurado, que activamente subió lo que le pedían, no recibe ningún reconocimiento de esa acción — la pantalla dice literalmente "Recibimos tu denuncia", como si estuviera arrancando de cero.

**Fix sugerido:** o el backend no vuelve a `PENDING_CLASSIFICATION` sino a un estado propio tipo `DOCUMENTATION_RECEIVED_REPROCESSING`, o el frontend detecta (por historial de transiciones) que hubo una carga de documentación previa y muestra un título distinto tipo *"Recibimos tu documentación, estamos re-evaluando tu caso"* en vez del genérico de día 1.

---

## 11. "Sin Datos" en la sección de IA — mensaje poco descriptivo

Contexto: aparece como fallback en secciones que dependen de datos que todavía no llegaron (ej. el análisis del modelo, el forense de imágenes, o el nav de "Pendientes" del punto 6 con el mismo `title="Sin datos"`).

`"Sin Datos"` no le dice al usuario **por qué** no hay datos ni **qué esperar**: ¿está cargando? ¿falló? ¿nunca va a haber datos para este caso? Un mensaje así genera la duda de si es un bug o un estado esperado.

**Fix sugerido (según lo pedido):** reemplazar por un texto más descriptivo del estado real, por ejemplo *"Pendiente de recepción de datos de IA"* o, si aplica, distinguir explícitamente "está procesando" de "no aplica para este caso" de "falló el análisis" — cada uno con su propio mensaje, no un genérico compartido.

---

## 12. "Hay que agregar un boton de descarga en el analisis de imagen

Contexto: Del lado del analista se pueden ver las imagenes que los asegurados cargan a su expediente y necesitaríamos un boton que permita bajar la foto para tenerla en local

**Fix sugerido (según lo pedido):** Añadir el boton

---

## 13. El analista ve el formulario de carga de archivos como si fuera el asegurado

**Archivos:** [`expediente-detail.component.html:117-122`](../arbiter-frontend/src/app/features/expedientes/expediente-detail/expediente-detail.component.html), [`expediente-detail.component.ts:269-271`](../arbiter-frontend/src/app/features/expedientes/expediente-detail/expediente-detail.component.ts)

Confirmado con el código exacto. Cuando un expediente tiene clasificación "Falta documentación", la pantalla de detalle del **analista** renderiza el mismo componente de carga que usa el **asegurado** en `portal/documentacion`:

```html
@if (needsDocs()) {
  <app-card class="missing-docs-card" heading="⚠ Falta documentación">
    <app-doc-upload [caseId]="d.id" (uploaded)="onDocsUploaded()" />
  </app-card>
}
```

```ts
protected readonly needsDocs = computed(() =>
  this.data()?.analysisClassification === 'FALTA_DOCUMENTACION'
);
```

`needsDocs()` solo mira la clasificación del caso — **cero chequeo de rol**. `app-doc-upload` es el mismo componente reusado tal cual entre las dos pantallas. El resultado: cualquier analista que abre un expediente en ese estado ve un formulario de carga de archivos activo y funcional, como si él tuviera que adjuntar la documentación del asegurado.

No es solo un problema de UX confusa — es un problema de integridad: el analista **no debería poder subir documentación en nombre del asegurado**. Rompe la trazabilidad de quién adjuntó qué, y no tiene sentido que quien revisa sea quien carga.

**Fix sugerido:** sacar `app-doc-upload` de la vista del analista. En su lugar, mostrar un estado de **solo lectura** — qué documentos faltan según la agenda documental, y que ya se le notificó al asegurado — sin ninguna acción de carga disponible para el rol analista.

---

## Resumen para priorizar

| # | Ítem | Severidad | Esfuerzo estimado |
|---|------|-----------|--------------------|
| 13 | Analista puede subir documentación como si fuera el asegurado | 🔴 Alta — rompe integridad/trazabilidad | Bajo (sacar el componente reusado) |
| 9 | Paginación: buscador no resetea página | 🔴 Alta — datos incorrectos visibles | Bajo (1 línea) |
| 10 | Seguimiento retrocede tras subir documentación | 🔴 Alta — mala experiencia, parece bug grave | Medio |
| 2 | Mensaje de error genérico en login | 🟠 Media | Bajo |
| 3 | Modal eliminar usuario sin restricciones | 🟠 Media — riesgo de seguridad/operación | Medio |
| 7 | "Expedientes asignados" no existe | 🟠 Media — feature faltante, no bug | Alto (nueva feature) |
| 8 | Labels "Denunciado"/"Terminado" mal ubicados | 🟠 Media — depende del fix de #10 | Bajo, una vez resuelto #10 |
| 5 | Favicon no es el de Arbiter | 🟡 Baja | Muy bajo |
| 1 | Falta spinner en login | 🟡 Baja | Muy bajo |
| 4 | Notificaciones sin funcionalidad | 🟡 Baja — es un stub conocido | Alto (requiere backend) |
| 6 | "Pendientes" bloqueado | 🟡 Baja — placeholder honesto | Alto (nueva pantalla) |
| 11 | "Sin Datos" poco descriptivo | 🟡 Baja | Muy bajo |
| 12 | Boton de descarga de imagen | 🟡 Baja | Muy bajo |
