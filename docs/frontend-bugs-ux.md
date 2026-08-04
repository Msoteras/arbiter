# Bugs y deuda de UX — frontend

**Estado:** documentado, sin fixear salvo donde se aclare. Cada ítem tiene el archivo/línea exacto
y la causa verificada en código — no son suposiciones, se leyó el componente correspondiente.

**Última revisión:** 3/8/2026, sobre `develop`. En esa pasada se **borraron 7 ítems ya resueltos**
(spinner de login, mensajes de error del login, doble verbo al eliminar usuario, "Pendientes"
hardcodeado en el nav, reseteo de página del buscador, botón de descarga de documentos, y el
formulario de carga que veía el analista) y se sumaron los que aparecen abajo como **nuevos**.

---

## 🔴 1. La decisión del analista no funciona: `analystId` es un string hardcodeado

**Archivos:** [`expediente-detail.component.ts:245-248`](../arbiter-frontend/src/app/features/expedientes/expediente-detail/expediente-detail.component.ts), [`expediente.service.ts:28-31`](../arbiter-frontend/src/app/features/expedientes/expediente.service.ts)

El front manda:

```ts
const decisionPayload: AnalystDecisionRequest = {
  analystId: 'analista-ui',          // ← string fijo, no el analista logueado
  decision: verb === 'aprobar' ? 'APPROVE' : 'REJECT',
};
```

y su interfaz lo declara `analystId: string`. El backend espera otra cosa:

```java
public record AnalystDecisionRequest(
        @NotNull(message = "analystId is required") Long analystId,
        ...
```

`case_classification.analyst_id` es **FK real** a `claims_analyst(id)`. Un string no parsea a
`Long` → 400. **Aprobar o rechazar un expediente no funciona hoy**, y es el corazón del
human-in-the-loop (decisión de arquitectura #5): sin esto ninguna clasificación puede impactar
en el expediente.

**Fix sugerido:** que el front no mande `analystId` en absoluto. cases-service ya resuelve el
tenant y la identidad desde el JWT (`CallerContext`); debería resolver también el
`claims_analyst.id` del analista logueado, igual que ya hace con `classificationAttempts`. El
Javadoc del DTO dice exactamente eso ("cases-service resolves it from the caller's JWT"), pero
todavía lo recibe del cliente.

---

## 🔴 2. La justificación del analista se pide y se tira

**Archivos:** [`expediente-detail.component.ts:234-248`](../arbiter-frontend/src/app/features/expedientes/expediente-detail/expediente-detail.component.ts), [`AnalystDecisionRequest.java`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/dto/AnalystDecisionRequest.java)

`confirmDecision()` **exige** la justificación antes de dejar continuar:

```ts
if (!this.justification().trim() || !verb) {
  return;
}
```

…y después arma el payload **sin incluirla**. El DTO solo tiene `analystId`, `decision` y
`classificationAttempts`. La columna `case_classification.analyst_justification TEXT` existe en
el esquema y queda siempre en null.

Es el registro auditable de *por qué* se decidió algo — justo lo que pide la Disposición SSN
2/2023. Pedirle al analista que la escriba y descartarla en silencio es peor que no pedirla.

**Fix sugerido:** sumar `justification` al DTO de las dos puntas y persistirla en
`case_classification.analyst_justification`.

---

## 🔴 3. El wizard deja enviar denuncias que el backend rechaza

**Archivos:** [`nueva-denuncia.component.ts:137, 218-219`](../arbiter-frontend/src/app/features/expedientes/nueva-denuncia/nueva-denuncia.component.ts), [`CaseRequest.java`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/dto/CaseRequest.java)

La validación del paso 2 mira **un solo campo**:

```ts
protected readonly step2Valid = computed(() => this.description().trim().length > 0);
```

Pero el backend exige además `insuredItem`, `eventDate` y `eventLocation`
(`@NotBlank`/`@NotNull`). Dos formas concretas de romperlo:

- **Sin fecha:** se arma `eventDate: this.eventDate() + 'T' + (this.eventTime() || '00:00') + ':00'`
  → con `eventDate()` vacío queda `"T00:00:00"`, que ni siquiera parsea como `LocalDateTime`.
- **Sin ubicación:** `buildEventLocation()` devuelve `''` si los tres campos están vacíos.

El usuario se entera recién en el paso 3, después de haber adjuntado documentación, con el
mensaje genérico `'Error al crear el caso'`. Ninguno de esos campos está marcado como
obligatorio en la UI (no hay asterisco ni nota).

**Fix sugerido:** sumar los tres a `step2Valid`, marcarlos visualmente como obligatorios y
mostrar el error al lado del campo, no al final del wizard.

---

## 🟠 4. "Guardado automático" es una promesa falsa

**Archivo:** [`nueva-denuncia.component.html:24, 205`](../arbiter-frontend/src/app/features/expedientes/nueva-denuncia/nueva-denuncia.component.html)

```html
<p class="wizard-sub">Te guiamos paso a paso. Podés volver atrás cuando quieras — nada se pierde.</p>
...
<span class="actions-hint">Paso {{ step() }} de 3 · guardado automático</span>
```

No existe ninguna persistencia: ni `localStorage`, ni borrador en backend. Todo vive en signals
en memoria. Si el usuario recarga, se le corta la conexión o cierra la pestaña por accidente,
**pierde todo** — justo después de que la interfaz le prometió lo contrario. El asegurado ya
cargó descripción, ubicación, fecha y hasta archivos.

**Fix sugerido:** o se implementa el borrador (`localStorage` alcanza para el caso), o se saca
el texto. Prometer y no cumplir es peor que no prometer.

---

## 🟠 5. El referente de aseguradora tiene una sola pantalla

**Archivos:** [`app.routes.ts:99-106`](../arbiter-frontend/src/app/app.routes.ts), [`app.html:19`](../arbiter-frontend/src/app/app.html), [`login.component.ts:59`](../arbiter-frontend/src/app/features/auth/login/login.component.ts)

`REFERENTE_ASEGURADORA` es uno de los tres roles del sistema (decisión #8) y el único destino que
tiene es `/insurer/users`. El login lo manda ahí directo y el nav no le ofrece nada más.

Lo llamativo es que el `roleGuard` **sí le da acceso total**:

```ts
if (current.rol === 'REFERENTE_ASEGURADORA') {
  return true;
}
```

O sea que podría entrar a la bandeja o al detalle de un expediente escribiendo la URL a mano,
pero no hay ningún link que lo lleve. Es un rol central sin navegación propia.

**Fix sugerido:** definir qué ve el referente (¿bandeja de solo lectura? ¿tablero?) y darle
entradas en el nav, o acotar el guard si no debería ver eso.

---

## 🟠 6. `reports-service` no lo consume nadie

**Archivos:** [`proxy.conf.json`](../arbiter-frontend/proxy.conf.json), todo el front

Cero referencias a reportes, métricas o tableros en el frontend. `proxy.conf.json` solo rutea
`/api/v1/auth` (8080), `/api/v1/cases` y `/api/v1/policies` (8083), y `/api/v1/claims` +
`/api/v1/classifications` (8082) — el puerto **8084 no está**, así que ni siquiera es alcanzable
desde la SPA en desarrollo.

El módulo corre, tiene su entidad `Metric`, y es inaccesible. Es el módulo de "Reportes y
Estadísticas" del documento de arquitectura, y el consumidor natural sería el referente (ver #5).

**Fix sugerido:** decidir si entra en el alcance del TP. Si entra, sumar la ruta al proxy y una
pantalla para el referente; si no, dejarlo anotado como fuera de alcance para que no parezca un
olvido.

---

## 🟠 7. "¿Cuáles son tus expedientes asignados?" — se sigue mostrando todo

**Archivos:** [`Case.java`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/models/entities/Case.java), [`bandeja.component.ts`](../arbiter-frontend/src/app/features/expedientes/bandeja/bandeja.component.ts)

El esquema **sí** tiene la columna (`cases.analyst_id BIGINT REFERENCES claims_analyst(id)`),
pero la entidad `Case` no la mapea, `GET /api/v1/cases` no filtra por analista, y la bandeja
muestra todos los expedientes a cualquier analista logueado.

Sabemos que **Flor lo está tomando en su historia**, así que queda acá solo para no perderlo de
vista, no como trabajo a repartir.

---

## 🟠 8. El seguimiento del asegurado retrocede después de subir documentación

**Archivos:** [`CaseServiceImpl.java:130`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/CaseServiceImpl.java), [`estado.ts:91`](../arbiter-frontend/src/app/core/models/estado.ts), [`documentacion.component.ts`](../arbiter-frontend/src/app/features/portal/documentacion/documentacion.component.ts)

Cadena completa, verificada de punta a punta:

1. El asegurado sube lo que le faltaba y el front lo devuelve a `/portal/cases/{id}`.
2. El backend resetea el estado a `PENDING_CLASSIFICATION` (motivo `"documentación adicional subida"`).
3. `estado.ts` mapea ese estado a `DENUNCIADO`, el primer escalón del stepper.
4. El seguimiento muestra *"Recibimos tu denuncia"* — el mismo texto que ve alguien que denunció
   recién.

El asegurado que hizo exactamente lo que le pidieron ve que su expediente **vuelve al día 1**.
No hay ningún reconocimiento de la acción.

Esto arrastra el ítem viejo de las etiquetas `DENUNCIADO`/`EN_TRAMITE`/`TERMINADO`: el problema
no son los labels, es que el estado técnico retrocede.

**Fix sugerido:** un estado propio de reproceso (tipo `DOCUMENTATION_RECEIVED`) que siga
mapeando a `EN_TRAMITE`, o que el front detecte por el historial de transiciones que ya hubo una
carga y cambie el título a *"Recibimos tu documentación, estamos re-evaluando tu caso"*.

---

## 🟡 9. El ícono de notificaciones sigue sin funcionalidad

**Archivos:** [`notifications.service.ts`](../arbiter-frontend/src/app/core/notifications/notifications.service.ts), [`app.html:36-52, 70-73`](../arbiter-frontend/src/app/app.html)

Sin cambios desde el relevamiento anterior: `setUnread` **no se llama desde ningún lado** (el
único hit del grep es su propia definición y el comentario que lo explica). No hay polling ni
SSE. El contador siempre vale 0 y la campana solo llama a `markAllRead()`, que limpia algo que
nunca se llenó.

Hay una tabla `notification` en el esquema de cada tenant y el seed carga una fila, así que el
dato existe del lado de la base — falta el endpoint y el cableado.

**Fix sugerido:** o se cablea, o se oculta hasta que exista el backend.

---

## 🟡 10. Sin validación de archivos en el wizard

**Archivo:** [`nueva-denuncia.component.ts:175-183`](../arbiter-frontend/src/app/features/expedientes/nueva-denuncia/nueva-denuncia.component.ts)

`onFileChange` toma el archivo y lo guarda sin mirar nada:

```ts
const file = input.files?.[0] ?? null;
```

El `accept="image/*,.pdf"` del input es solo una sugerencia del explorador de archivos (se puede
saltear eligiendo "todos los archivos"), y **no hay ningún límite de tamaño**. Un PDF de 80 MB se
acepta en la UI y falla recién en el submit, después de esperar la subida, con el mensaje
genérico.

**Fix sugerido:** validar tipo y tamaño en `onFileChange` y mostrar el error en el slot del
documento, antes de llegar al envío.

---

## 🟡 11. Se puede denunciar un siniestro con fecha futura

**Archivo:** [`nueva-denuncia.component.html:118`](../arbiter-frontend/src/app/features/expedientes/nueva-denuncia/nueva-denuncia.component.html)

```html
<app-input type="date" [(value)]="eventDate" />
```

Sin `max`, y sin validación en el submit. Se puede cargar una denuncia de un hecho que "ocurre"
la semana que viene. El backend tampoco lo valida (`@NotNull LocalDateTime eventDate`, nada más).

**Fix sugerido:** `max` = hoy en el input y validación en backend, que es donde tiene que estar
la regla de verdad.

---

## 🟡 12. Faltan estados vacíos en casi todas las pantallas

**Archivos:** `bandeja`, `seguimiento`, `documentacion`, `usuarios`

Existe el componente `app-empty-state` en el design system y **solo lo usa `mis-expedientes`**.
Las otras cuatro pantallas tienen estado de carga y de error, pero cuando la lista vuelve vacía
no muestran nada: la tabla queda en blanco sin explicación. El usuario no puede distinguir "no
hay resultados para este filtro" de "algo se rompió".

**Fix sugerido:** usar `app-empty-state` en las cuatro, con mensajes distintos según el caso
("no hay expedientes con estos filtros" vs. "todavía no cargaste ningún usuario").

---

## 🟡 13. `/styleguide` es pública

**Archivo:** [`app.routes.ts:53-57`](../arbiter-frontend/src/app/app.routes.ts)

Es la única ruta sin `canActivate: [roleGuard]`. Cualquiera sin sesión puede abrir
`/styleguide` y ver el design system completo. Además el nav la muestra siempre, con clase
`nav-dev`.

No expone datos, pero es una pantalla de desarrollo accesible en producción.

**Fix sugerido:** guardarla detrás del guard, o excluirla del build productivo.

---

## 🟡 14. El favicon viejo de Angular sigue en `public/`

**Archivos:** `arbiter-frontend/public/favicon.ico`, `favicon.svg`

Sin cambios: los dos archivos conviven. El `index.html` referencia el `.svg` (el de Arbiter),
pero los navegadores piden `/favicon.ico` por convención sin importar el `<link>`, así que es
probable que se siga viendo el genérico — sobre todo con la pestaña cacheada.

**Fix sugerido:** regenerar el `.ico` desde el diseño de Arbiter o borrarlo.

---

## 🟡 15. "Sin datos" no distingue por qué no hay datos

**Archivos:** [`empty-state.component.ts:27-30`](../arbiter-frontend/src/app/shared/ui/empty-state/empty-state.component.ts), [`fraud-gauge.component.ts:59`](../arbiter-frontend/src/app/shared/ui/fraud-gauge/fraud-gauge.component.ts), [`expediente-detail.component.html:102`](../arbiter-frontend/src/app/features/expedientes/expediente-detail/expediente-detail.component.html)

`"Sin datos"` es el default de `app-empty-state` y también el texto del medidor de fraude cuando
la banda es `null`. El mismo texto cubre situaciones distintas: *está procesando*, *no aplica a
este caso* (un Fast Track no tiene análisis del modelo), y *falló el análisis*. El analista no
puede saber si esperar o si hay un problema.

Vale la pena distinguir al menos "sin scorear" (nunca se calculó, es esperable) de "en proceso".

**Fix sugerido:** pasar un `message`/`sub` explícito en cada uso en vez de dejar el default.

---

## 🟡 16. En el wizard no se puede saltar hacia adelante

**Archivo:** [`nueva-denuncia.component.ts:168-173`](../arbiter-frontend/src/app/features/expedientes/nueva-denuncia/nueva-denuncia.component.ts)

```ts
goToStep(s: Step): void {
  if (s < this.step()) {
    this.step.set(s);
  }
}
```

Volver atrás desde el paso 3 al 1 funciona, pero para regresar al 3 hay que apretar "Continuar"
dos veces aunque no se haya cambiado nada. Los pasos ya completados deberían ser navegables en
las dos direcciones.

**Fix sugerido:** permitir avanzar hasta el paso máximo ya alcanzado.

---

## Resumen para priorizar

| # | Ítem | Severidad | Esfuerzo |
|---|------|-----------|----------|
| 1 | `analystId` hardcodeado rompe aprobar/rechazar | 🔴 Alta — funcionalidad central caída | Bajo |
| 2 | La justificación del analista se descarta | 🔴 Alta — auditoría incompleta (SSN 2/2023) | Bajo |
| 3 | El wizard envía denuncias que el backend rechaza | 🔴 Alta — bloquea al usuario final | Bajo |
| 4 | "Guardado automático" no existe | 🟠 Media — promesa falsa, pérdida de datos | Bajo (o sacar el texto) |
| 5 | El referente tiene una sola pantalla | 🟠 Media — rol sin navegación | Alto |
| 6 | `reports-service` inalcanzable desde el front | 🟠 Media — módulo entero sin consumir | Alto |
| 7 | Expedientes asignados (lo toma Flor) | 🟠 Media | — |
| 8 | El seguimiento retrocede tras subir documentación | 🟠 Media — parece un bug grave al asegurado | Medio |
| 10 | Sin validación de archivos | 🟡 Baja | Bajo |
| 11 | Fecha de siniestro futura | 🟡 Baja | Bajo |
| 12 | Faltan estados vacíos | 🟡 Baja | Bajo |
| 13 | `/styleguide` pública | 🟡 Baja | Muy bajo |
| 16 | No se puede avanzar en el wizard | 🟡 Baja | Muy bajo |
| 9 | Notificaciones sin cablear | 🟡 Baja — stub conocido | Alto (requiere backend) |
| 14 | Favicon viejo | 🟡 Baja | Muy bajo |
| 15 | "Sin datos" ambiguo | 🟡 Baja | Bajo |
