# Bugs y deuda de UX — frontend

**Estado:** documentado, sin fixear salvo donde se aclare. Cada ítem tiene el archivo/línea exacto
y la causa verificada en código — no son suposiciones, se leyó el componente correspondiente.

**Última revisión:** 3/8/2026, sobre `develop`. La tercera pasada se hizo **con la app corriendo**
contra la BD de Railway, no solo leyendo código: los ítems 4, 5 y 18 están verificados en runtime
(árbol de accesibilidad, navegación real, DOM inspeccionado). En esa pasada se **borraron 7 ítems ya resueltos**
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

## 🟠 7. "Guardado automático" es una promesa falsa

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

## 🟠 8. El referente de aseguradora tiene una sola pantalla

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

## 🟠 9. `reports-service` no lo consume nadie

**Archivos:** [`proxy.conf.json`](../arbiter-frontend/proxy.conf.json), todo el front

Cero referencias a reportes, métricas o tableros en el frontend. `proxy.conf.json` solo rutea
`/api/v1/auth` (8080), `/api/v1/cases` y `/api/v1/policies` (8083), y `/api/v1/claims` +
`/api/v1/classifications` (8082) — el puerto **8084 no está**, así que ni siquiera es alcanzable
desde la SPA en desarrollo.

El módulo corre, tiene su entidad `Metric`, y es inaccesible. Es el módulo de "Reportes y
Estadísticas" del documento de arquitectura, y el consumidor natural sería el referente (ver #8).

**Fix sugerido:** decidir si entra en el alcance del TP. Si entra, sumar la ruta al proxy y una
pantalla para el referente; si no, dejarlo anotado como fuera de alcance para que no parezca un
olvido.

---

## 🟠 10. "¿Cuáles son tus expedientes asignados?" — se sigue mostrando todo

**Archivos:** [`Case.java`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/models/entities/Case.java), [`bandeja.component.ts`](../arbiter-frontend/src/app/features/expedientes/bandeja/bandeja.component.ts)

El esquema **sí** tiene la columna (`cases.analyst_id BIGINT REFERENCES claims_analyst(id)`),
pero la entidad `Case` no la mapea, `GET /api/v1/cases` no filtra por analista, y la bandeja
muestra todos los expedientes a cualquier analista logueado.

Sabemos que **Flor lo está tomando en su historia**, así que queda acá solo para no perderlo de
vista, no como trabajo a repartir.

---

## 🟠 11. El seguimiento del asegurado retrocede después de subir documentación

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

## 🔴 4. Ningún campo de formulario tiene nombre accesible

**Archivos:** [`input.component.ts:90-99`](../arbiter-frontend/src/app/shared/ui/input/input.component.ts), [`login.component.html:18-31`](../arbiter-frontend/src/app/features/auth/login/login.component.html) (y las otras 33 usos de `field-label`)

**Verificado en runtime**, leyendo el árbol de accesibilidad de `/login` con la app corriendo:

```
textbox [ref_1] type="email"          ← sin nombre
textbox [ref_3] type="password"       ← sin nombre
button  "Mostrar contraseña" [ref_4]  ← con nombre
button  "Ingresar →" [ref_5]          ← con nombre
```

Los botones tienen nombre accesible; **los campos no**. Un lector de pantalla los anuncia como
"cuadro de texto" en blanco, sin decir cuál es cuál.

La causa es más sutil de lo que parece: el markup **sí** hace lo correcto…

```html
<label class="field-label" for="login-email">Email</label>
<app-input ... />
```

…pero `InputComponent` **no declara ningún input `id`**:

```ts
readonly value = model('');
readonly type = input<...>('text');
readonly placeholder = input('');
readonly min = input<number | null>(null);
readonly autocomplete = input<string | null>(null);
readonly readonly = input(false);
readonly revealable = input(false);
// ← no hay `id`
```

y su template renderiza el `<input>` sin `id`. Confirmado en el DOM: los dos inputs tienen
`id: null`, `aria-label: null`, `aria-labelledby: null` y no están anidados dentro del label,
mientras los labels apuntan a `for="login-email"` / `for="login-password"` — ids que **no existen
en la página**. El `for` no engancha con nada.

Es sistémico, no del login: `field-label` se usa 34 veces (todo el wizard de denuncia, los
filtros de la bandeja, el alta de usuario) y `app-input` es el componente estándar del design
system. Rompe el criterio de accesibilidad que el propio CLAUDE.md fija para el kit de UI, y
además el click en el label no enfoca el campo, que es una molestia para cualquiera, no solo
para quien usa lector de pantalla.

**Fix sugerido:** agregar `readonly id = input<string | null>(null)` a `InputComponent` (y a
`app-textarea` / `app-select`, que muy probablemente tengan lo mismo) y bindearlo con
`[attr.id]`. Como red de seguridad, si no viene `id` se puede generar uno y exponerlo, para que
ningún uso quede sin asociar.

---

## 🟠 5. Una URL inexistente deja la página en blanco

**Archivo:** [`app.routes.ts:5, 112`](../arbiter-frontend/src/app/app.routes.ts)

```ts
{ path: '', redirectTo: 'login', pathMatch: 'full' },
...
{ path: '**', redirectTo: '' },
```

**Verificado en runtime.** Navegando a `/ruta-que-no-existe`:

| | Resultado |
|---|---|
| URL final | `/` (no `/login`) |
| `<router-outlet>` | existe |
| Componente renderizado | **ninguno** |
| Texto visible | solo el shell: "Arbiter / Design System" |

Comparado con `/login`, que sí renderiza `APP-LOGIN` correctamente — o sea que no es un problema
general del router, es específico del comodín.

El redirect del `**` deja la navegación en `''` pero la regla `'' → login` no se vuelve a
aplicar, así que el outlet queda vacío. Para el usuario: un link roto, un typo en la URL o un
bookmark viejo dan una **pantalla en blanco**, sin mensaje ni forma de volver.

**Fix sugerido:** que el comodín apunte directo a `login` (`{ path: '**', redirectTo: 'login' }`)
o, mejor, una pantalla 404 real con un link al home del rol.

---

## 🟠 6. La sesión vence a los 60 minutos y nadie se entera

**Archivos:** [`auth.interceptor.ts`](../arbiter-frontend/src/app/core/http/auth.interceptor.ts), [`auth-session.service.ts`](../arbiter-frontend/src/app/core/auth/auth-session.service.ts)

El interceptor solo **adjunta** el token; no reacciona a la respuesta:

```ts
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(AuthSessionService).token();
  if (token && req.url.includes('/api/')) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }
  return next(req);   // ← ningún catchError, ningún manejo de 401
};
```

No hay manejo global de 401 en ningún lado (el único hit del grep está en un comentario de
`expediente.service.ts`). El token dura 60 minutos por defecto
(`JWT_EXPIRATION_MINUTES:60`). Cuando vence, **cada pantalla muestra su mensaje de error
genérico** — "No pudimos cargar…", "Error al crear el caso" — y el usuario no tiene forma de
saber que lo que pasó es que se le venció la sesión. Peor en el wizard: completa los tres pasos,
adjunta documentación, y el envío falla sin decirle que tiene que volver a loguearse.

Además, `expiresAt` se guarda en la sesión (`AuthSession.expiresAt`) y **no lo lee nadie** — el
grep solo encuentra las dos declaraciones del campo, ningún uso.

Se combina feo con el ítem #7: el token vive solo en memoria (decisión deliberada de H0001), así
que un F5 en medio del wizard pierde la sesión **y** el borrador al mismo tiempo.

**Fix sugerido:** manejar 401 en el interceptor — limpiar la sesión y redirigir a `/login` con un
mensaje del tipo "tu sesión expiró, ingresá de nuevo". Opcionalmente avisar antes usando
`expiresAt`, que ya está disponible.

---

## 🟠 12. La tabla de la bandeja desborda en móvil

**Archivos:** [`bandeja.component.scss:39-48`](../arbiter-frontend/src/app/features/expedientes/bandeja/bandeja.component.scss), [`bandeja.component.html:104-105`](../arbiter-frontend/src/app/features/expedientes/bandeja/bandeja.component.html)

Todas las celdas son `white-space: nowrap`:

```scss
th, td { text-align: left; padding: var(--space-3) var(--space-4); white-space: nowrap; }
```

y **no hay ningún `overflow-x`** en el componente (grep vacío). El único breakpoint a 720px solo
ajusta `.filter-field`, no la tabla. El `<table>` está directamente adentro de
`<app-card [flush]="true">`, sin wrapper scrolleable.

Con 8 columnas sin permitir corte de línea y sin contenedor que scrollee, en un teléfono la tabla
empuja el ancho de la página entera: aparece scroll horizontal en el `body` y se rompe el layout.
Va en contra del RNF de usabilidad (≥85% de éxito en tareas básicas **en PC y móvil**), y del
guardrail del design system, que pide que el contenido ancho scrollee dentro de su propio
contenedor.

**Fix sugerido:** envolver la tabla en un contenedor con `overflow-x: auto`, o pasar a un layout
de tarjetas por debajo del breakpoint móvil.

---

## 🟡 13. Tres pantallas sin ningún breakpoint

**Archivos:** `portal/documentacion`, `admin/usuarios`, `admin/alta-usuario`

El front sí tiene responsive en general (11 media queries repartidas), pero estas tres no tienen
ninguna. `usuarios` es la única pantalla del referente y muestra una tabla de usuarios con
acciones — el mismo riesgo que el ítem #10.

**Fix sugerido:** revisarlas en viewport móvil y sumar los breakpoints que falten.

---

## 🟡 14. El ícono de notificaciones sigue sin funcionalidad

**Archivos:** [`notifications.service.ts`](../arbiter-frontend/src/app/core/notifications/notifications.service.ts), [`app.html:36-52, 70-73`](../arbiter-frontend/src/app/app.html)

Sin cambios desde el relevamiento anterior: `setUnread` **no se llama desde ningún lado** (el
único hit del grep es su propia definición y el comentario que lo explica). No hay polling ni
SSE. El contador siempre vale 0 y la campana solo llama a `markAllRead()`, que limpia algo que
nunca se llenó.

Hay una tabla `notification` en el esquema de cada tenant y el seed carga una fila, así que el
dato existe del lado de la base — falta el endpoint y el cableado.

**Fix sugerido:** o se cablea, o se oculta hasta que exista el backend.

---

## 🟡 15. Sin validación de archivos en el wizard

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

## 🟡 16. Se puede denunciar un siniestro con fecha futura

**Archivo:** [`nueva-denuncia.component.html:118`](../arbiter-frontend/src/app/features/expedientes/nueva-denuncia/nueva-denuncia.component.html)

```html
<app-input type="date" [(value)]="eventDate" />
```

Sin `max`, y sin validación en el submit. Se puede cargar una denuncia de un hecho que "ocurre"
la semana que viene. El backend tampoco lo valida (`@NotNull LocalDateTime eventDate`, nada más).

**Fix sugerido:** `max` = hoy en el input y validación en backend, que es donde tiene que estar
la regla de verdad.

---

## 🟡 17. Faltan estados vacíos en casi todas las pantallas

**Archivos:** `bandeja`, `seguimiento`, `documentacion`, `usuarios`

Existe el componente `app-empty-state` en el design system y **solo lo usa `mis-expedientes`**.
Las otras cuatro pantallas tienen estado de carga y de error, pero cuando la lista vuelve vacía
no muestran nada: la tabla queda en blanco sin explicación. El usuario no puede distinguir "no
hay resultados para este filtro" de "algo se rompió".

**Fix sugerido:** usar `app-empty-state` en las cuatro, con mensajes distintos según el caso
("no hay expedientes con estos filtros" vs. "todavía no cargaste ningún usuario").

---

## 🟡 18. `/styleguide` es pública

**Archivo:** [`app.routes.ts:53-57`](../arbiter-frontend/src/app/app.routes.ts)

Es la única ruta sin `canActivate: [roleGuard]`. Cualquiera sin sesión puede abrir
`/styleguide` y ver el design system completo. Además el nav la muestra siempre, con clase
`nav-dev`.

No expone datos, pero es una pantalla de desarrollo accesible en producción.

**Fix sugerido:** guardarla detrás del guard, o excluirla del build productivo.

---

## 🟡 19. El favicon viejo de Angular sigue en `public/`

**Archivos:** `arbiter-frontend/public/favicon.ico`, `favicon.svg`

Sin cambios: los dos archivos conviven. El `index.html` referencia el `.svg` (el de Arbiter),
pero los navegadores piden `/favicon.ico` por convención sin importar el `<link>`, así que es
probable que se siga viendo el genérico — sobre todo con la pestaña cacheada.

**Fix sugerido:** regenerar el `.ico` desde el diseño de Arbiter o borrarlo.

---

## 🟡 20. "Sin datos" no distingue por qué no hay datos

**Archivos:** [`empty-state.component.ts:27-30`](../arbiter-frontend/src/app/shared/ui/empty-state/empty-state.component.ts), [`fraud-gauge.component.ts:59`](../arbiter-frontend/src/app/shared/ui/fraud-gauge/fraud-gauge.component.ts), [`expediente-detail.component.html:102`](../arbiter-frontend/src/app/features/expedientes/expediente-detail/expediente-detail.component.html)

`"Sin datos"` es el default de `app-empty-state` y también el texto del medidor de fraude cuando
la banda es `null`. El mismo texto cubre situaciones distintas: *está procesando*, *no aplica a
este caso* (un Fast Track no tiene análisis del modelo), y *falló el análisis*. El analista no
puede saber si esperar o si hay un problema.

Vale la pena distinguir al menos "sin scorear" (nunca se calculó, es esperable) de "en proceso".

**Fix sugerido:** pasar un `message`/`sub` explícito en cada uso en vez de dejar el default.

---

## 🟡 21. En el wizard no se puede saltar hacia adelante

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
| 4 | Ningún campo tiene nombre accesible | 🔴 Alta — sistémico, 34 usos | Bajo (un input en el kit) |
| 5 | URL inexistente = pantalla en blanco | 🟠 Media — sin salida para el usuario | Muy bajo |
| 6 | La sesión vence y nadie se entera (sin manejo de 401) | 🟠 Media — errores confusos en toda la app | Bajo |
| 12 | La tabla de la bandeja desborda en móvil | 🟠 Media — incumple el RNF de usabilidad | Bajo |
| 7 | "Guardado automático" no existe | 🟠 Media — promesa falsa, pérdida de datos | Bajo (o sacar el texto) |
| 11 | El seguimiento retrocede tras subir documentación | 🟠 Media — parece un bug grave al asegurado | Medio |
| 8 | El referente tiene una sola pantalla | 🟠 Media — rol sin navegación | Alto |
| 9 | `reports-service` inalcanzable desde el front | 🟠 Media — módulo entero sin consumir | Alto |
| 10 | Expedientes asignados (lo toma Flor) | 🟠 Media | — |
| 15 | Sin validación de archivos | 🟡 Baja | Bajo |
| 16 | Fecha de siniestro futura | 🟡 Baja | Bajo |
| 17 | Faltan estados vacíos | 🟡 Baja | Bajo |
| 13 | Tres pantallas sin breakpoint | 🟡 Baja | Bajo |
| 18 | `/styleguide` pública | 🟡 Baja | Muy bajo |
| 21 | No se puede avanzar en el wizard | 🟡 Baja | Muy bajo |
| 14 | Notificaciones sin cablear | 🟡 Baja — stub conocido | Alto (requiere backend) |
| 19 | Favicon viejo | 🟡 Baja | Muy bajo |
| 20 | "Sin datos" ambiguo | 🟡 Baja | Bajo |
