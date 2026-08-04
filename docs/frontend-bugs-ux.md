# Bugs y deuda de UX — frontend

**Estado:** documentado, sin fixear salvo donde se aclare. Cada ítem tiene el archivo/línea exacto
y la causa verificada en código — no son suposiciones, se leyó el componente correspondiente.

**Última revisión:** 4/8/2026, sobre `develop`. La tercera pasada (3/8) se hizo **con la app corriendo**
contra la BD de Railway, no solo leyendo código: los ítems 4, 5 y 18 están verificados en runtime
(árbol de accesibilidad, navegación real, DOM inspeccionado). En esa pasada se **borraron 7 ítems ya resueltos**
(spinner de login, mensajes de error del login, doble verbo al eliminar usuario, "Pendientes"
hardcodeado en el nav, reseteo de página del buscador, botón de descarga de documentos, y el
formulario de carga que veía el analista) y se sumaron los que aparecen abajo como **nuevos**.

**Pasada del 4/8 (mañana):** se arreglaron los 4 🔴 y la mayoría de los 🟠🟡 de este documento
(commits `8a10d8d` y `a238a61`, pusheados a `develop`). Cada ítem cerrado tiene ahora una nota
**✅ CERRADO** con lo que se hizo — se dejan las descripciones originales completas a propósito,
como registro de qué se arregló y por qué, no se borran.

**Pasada del 4/8 (tarde/noche) — testeo end-to-end nuevo, sin fixes:** sesión de testeo puro contra
Railway real (las 8 cuentas de [`arbiter-multitenant-live-verification`], los 5 backends + frontend
corriendo) para buscar bugs nuevos y revalidar lo cerrado. Se re-confirmaron en vivo los ítems 8, 9,
10, 13, 14, 17, 20 como **todavía abiertos** (con una salvedad en el 13, ver su sección) y se
verificó que no hay regresiones de seguridad (RBAC/IDOR: 401/403/404/409 responden donde
corresponde). Se suman **4 hallazgos nuevos** como ítems 22–25, más 2 de deuda de documentación
(26–27) que no son bugs funcionales pero vale la pena anotar.

---

## 🔴 1. La decisión del analista no funciona: `analystId` es un string hardcodeado

> ✅ **CERRADO (4/8, commit `8a10d8d`).** `analystId` ya no viaja del cliente:
> `CaseServiceImpl.recordAnalystDecision` lo resuelve del JWT (`SecurityContextHolder`) contra
> `ClaimsAnalystRepository.findByEmail`. Requirió mover `ClaimsAnalyst` a
> `common/models/entities/tenant/` y sumar `AnalystProfileNotFoundException` (403) para el caso de
> un referente sin perfil de analista. **Verificado en vivo dos veces:** el 4/8 con Lucas Gómez
> (BBVA) y el mismo día en el testeo de la tarde con Diego Fernández (Provincia) — decisión real
> desde la bandeja, `analyst_id` correcto en `case_classification` confirmado por consulta directa
> a la base.

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

> ✅ **CERRADO (4/8, commit `8a10d8d`).** Se sumó `justification` a `AnalystDecisionRequest` (cases
> y classification-service) y se persiste en `case_classification.analyst_justification`.
> **Verificado en vivo:** el mismo texto escrito en el modal de decisión aparece tal cual en la
> columna de la base al consultarla directo (`SELECT ... FROM case_classification`), tanto para
> BBVA como para Provincia.

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

> ✅ **CERRADO (4/8, commit `8a10d8d`).** `insuredItem`/`eventDate`/`eventLocation` se sumaron a
> `step2Valid` junto con `description`, marcados con `*` en la UI. **Verificado en vivo:** con solo
> `description` cargada "Continuar" queda bloqueado; al completar los 4 campos se habilita. Wizard
> completo probado de punta a punta (denuncia real #8 creada en Railway).

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

> ✅ **CERRADO (4/8, commit `a238a61`).** Se sacó el texto en vez de prometer algo que no existe
> (no había borrador que implementar en el alcance de esta pasada).

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

> **Sigue abierto, re-verificado 4/8 tarde.** Confirmado en código y en vivo: `roleGuard.ts:21-23`
> sigue dejando pasar a cualquier ruta a un referente (`if (current.rol === 'REFERENTE_ASEGURADORA')
> return true`), pero solo `Usuarios` está en el nav. Necesita que el equipo defina qué debería ver
> antes de construir nada — no es un fix directo.

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

> **Sigue abierto, re-verificado 4/8 tarde.** `reports-service` arranca OK contra Railway (parte de
> los 5 módulos levantados para el testeo), pero sigue inalcanzable desde el front. Necesita decidir
> si entra en el alcance del TP.

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

> **Sigue abierto, re-verificado 4/8 tarde.** Sigue en manos de Flor, no tocado en esta pasada.

**Archivos:** [`Case.java`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/models/entities/Case.java), [`bandeja.component.ts`](../arbiter-frontend/src/app/features/expedientes/bandeja/bandeja.component.ts)

El esquema **sí** tiene la columna (`cases.analyst_id BIGINT REFERENCES claims_analyst(id)`),
pero la entidad `Case` no la mapea, `GET /api/v1/cases` no filtra por analista, y la bandeja
muestra todos los expedientes a cualquier analista logueado.

Sabemos que **Flor lo está tomando en su historia**, así que queda acá solo para no perderlo de
vista, no como trabajo a repartir.

---

## 🟠 11. El seguimiento del asegurado retrocede después de subir documentación

> ✅ **CERRADO — ya estaba arreglado de un pase anterior a la pasada del 4/8** (no de los commits
> `8a10d8d`/`a238a61`, de un fix previo). `estadoSimplificadoEfectivo`/`esReprocesoPorDocumentacion`
> en `estado.ts` resuelven el título/estado correcto (`"Recibimos tu documentación"` en vez de
> volver a `"Recibimos tu denuncia"`). Verificado el 4/8: no hizo falta tocar nada, sigue funcionando.

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

> ✅ **CERRADO (4/8, commit `8a10d8d`).** Se agregó `id` real a `InputComponent`, `TextareaComponent`
> y `SelectComponent` (con autogenerado como red de seguridad si no viene por input). **Verificado
> en runtime:** el árbol de accesibilidad de `/login` ahora nombra los dos campos correctamente.

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

> ✅ **CERRADO (4/8, commit `a238a61`).** El comodín `**` ahora apunta directo a `login` en vez de
> a `''` (que no revalidaba el redirect). Verificado: `/ruta-que-no-existe` redirige a `/login`.

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

> ✅ **CERRADO (4/8, commit `a238a61`).** `authInterceptor` ahora atrapa el 401 de cualquier
> `/api/*` (menos el propio login), limpia la sesión y redirige a `/login?sessionExpired=1`; el
> login lee ese query param y muestra "Tu sesión expiró. Ingresá de nuevo." en vez del error
> genérico de cada pantalla.

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

> ✅ **CERRADO (4/8, commit `a238a61`).** Envuelta en un contenedor con `overflow-x: auto` — el
> `app-table` compartido ahora lo trae de fábrica (`:host{overflow-x:auto}`), por lo que cualquier
> pantalla que use el componente lo hereda gratis. **Re-verificado en el testeo del 4/8 tarde:** en
> viewport 375px la tabla scrollea dentro de su propio contenedor, sin empujar la página. Nota
> menor encontrada de paso: `BandejaComponent` quedó importando `TableComponent` sin usarlo
> (warning de build `NG8113`) — ver ítem 26.

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

> **Sigue abierto, pero reevaluado a la baja (4/8 tarde).** Se probó `admin/usuarios` en viewport
> 375px con la app real: la tabla scrollea bien (hereda el `overflow-x:auto` del `app-table`
> compartido, ver ítem 12) y el formulario "Nuevo usuario" entra completo sin desbordar (medido por
> DOM: el botón "Crear usuario" queda en `right:387` con `innerWidth:411`, sin overflow). No se
> encontró nada roto ni inutilizable en esas dos pantallas — sigue sin haber breakpoint explícito,
> pero el layout no se rompe en la práctica gracias al componente compartido. **No se probó
> `portal/documentacion`** en esta pasada.

**Archivos:** `portal/documentacion`, `admin/usuarios`, `admin/alta-usuario`

El front sí tiene responsive en general (11 media queries repartidas), pero estas tres no tienen
ninguna. `usuarios` es la única pantalla del referente y muestra una tabla de usuarios con
acciones — el mismo riesgo que el ítem #10.

**Fix sugerido:** revisarlas en viewport móvil y sumar los breakpoints que falten.

---

## 🟡 14. El ícono de notificaciones sigue sin funcionalidad

> **Sigue abierto, re-verificado 4/8 tarde.** Probado en vivo como referente: el panel dice
> "No tenés notificaciones sin leer" — siempre vacío, tal como describe este ítem. Esfuerzo alto,
> necesita endpoint + polling/SSE.

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

> ✅ **CERRADO (4/8, commit `a238a61`).** Chequeo de tipo (imagen/PDF) y tamaño (10MB) en
> `onFileChange`, con el error al lado del slot. **De paso se encontró y arregló un bug más gordo**
> (no documentado antes): `cases-service` no tenía `spring.servlet.multipart.max-file-size`
> configurado, corría con el default de Spring (**1MB**) — menos de lo que pesa una foto de celular
> común. Subido a 10MB/archivo, 30MB/request en el backend, mismo tope que valida el front.

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

> ✅ **CERRADO (4/8, commit `a238a61`).** `max` = hoy en el datepicker (nuevo input `max` en
> `InputComponent`) + `@PastOrPresent` en `CaseRequest.eventDate` — la regla de verdad queda en el
> backend, que es donde tiene que estar. **Verificado en vivo:** `max="2026-08-04"` correcto en el
> input de fecha del wizard.

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

> **Sigue abierto, re-verificado 4/8 tarde.** Confirmado por código: `bandeja.component.html` sigue
> sin ningún `app-empty-state`. No se llegó a probar `seguimiento`/`documentacion`/`usuarios` con
> datos vacíos en esta pasada, pero el grep no encuentra el componente en ninguna de las cuatro.

**Archivos:** `bandeja`, `seguimiento`, `documentacion`, `usuarios`

Existe el componente `app-empty-state` en el design system y **solo lo usa `mis-expedientes`**.
Las otras cuatro pantallas tienen estado de carga y de error, pero cuando la lista vuelve vacía
no muestran nada: la tabla queda en blanco sin explicación. El usuario no puede distinguir "no
hay resultados para este filtro" de "algo se rompió".

**Fix sugerido:** usar `app-empty-state` en las cuatro, con mensajes distintos según el caso
("no hay expedientes con estos filtros" vs. "todavía no cargaste ningún usuario").

---

## 🟡 18. `/styleguide` es pública

> ✅ **CERRADO (4/8, commit `a238a61`).** Ahora detrás de `roleGuard` (cualquier sesión autenticada
> entra, sin restricción de rol — es la vitrina del design system, no de un rol en particular).

**Archivo:** [`app.routes.ts:53-57`](../arbiter-frontend/src/app/app.routes.ts)

Es la única ruta sin `canActivate: [roleGuard]`. Cualquiera sin sesión puede abrir
`/styleguide` y ver el design system completo. Además el nav la muestra siempre, con clase
`nav-dev`.

No expone datos, pero es una pantalla de desarrollo accesible en producción.

**Fix sugerido:** guardarla detrás del guard, o excluirla del build productivo.

---

## 🟡 19. El favicon viejo de Angular sigue en `public/`

> ✅ **CERRADO (4/8, commit `a238a61`).** Sin herramientas de imagen para regenerar un `.ico`
> propio, se borró el viejo y quedó el SVG de Arbiter como único favicon.

**Archivos:** `arbiter-frontend/public/favicon.ico`, `favicon.svg`

Sin cambios: los dos archivos conviven. El `index.html` referencia el `.svg` (el de Arbiter),
pero los navegadores piden `/favicon.ico` por convención sin importar el `<link>`, así que es
probable que se siga viendo el genérico — sobre todo con la pestaña cacheada.

**Fix sugerido:** regenerar el `.ico` desde el diseño de Arbiter o borrarlo.

---

## 🟡 20. "Sin datos" no distingue por qué no hay datos

> **Sigue abierto, re-verificado 4/8 tarde.** Visible en vivo en la columna RIESGO de la bandeja
> para varios expedientes (cuando `riskBand` es `null` porque nunca se calculó, no porque haya
> fallado). Mismo default ambiguo que describe este ítem.

**Archivos:** [`empty-state.component.ts:27-30`](../arbiter-frontend/src/app/shared/ui/empty-state/empty-state.component.ts), [`fraud-gauge.component.ts:59`](../arbiter-frontend/src/app/shared/ui/fraud-gauge/fraud-gauge.component.ts), [`expediente-detail.component.html:102`](../arbiter-frontend/src/app/features/expedientes/expediente-detail/expediente-detail.component.html)

`"Sin datos"` es el default de `app-empty-state` y también el texto del medidor de fraude cuando
la banda es `null`. El mismo texto cubre situaciones distintas: *está procesando*, *no aplica a
este caso* (un Fast Track no tiene análisis del modelo), y *falló el análisis*. El analista no
puede saber si esperar o si hay un problema.

Vale la pena distinguir al menos "sin scorear" (nunca se calculó, es esperable) de "en proceso".

**Fix sugerido:** pasar un `message`/`sub` explícito en cada uso en vez de dejar el default.

---

## 🟡 21. En el wizard no se puede saltar hacia adelante

> ✅ **CERRADO (4/8, commit `a238a61`).** `goToStep` ahora permite navegar a cualquier paso ya
> alcanzado, en las dos direcciones. **Verificado en vivo:** volver del paso 3 al 1 y avanzar de
> nuevo conserva los datos cargados.

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

## 🔴 22. `CLASSIFICATION_FAILED` es un callejón sin salida con mensaje falso

**Archivos:** [`ClassificationRefreshScheduler.java:60-77`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/ClassificationRefreshScheduler.java), [`CaseStatusService.java:33`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/CaseStatusService.java), [`estado.ts:156-193`](../arbiter-frontend/src/app/core/models/estado.ts)

El scheduler que reintenta la clasificación solo mira los pendientes:

```java
@Scheduled(fixedDelayString = "${arbiter.classification-refresh.interval-ms:5000}")
public void refreshPendingCases() { ... }

private void refreshPendingCasesForCurrentTenant() {
    List<Case> pending = caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION);
    ...
}
```

Cuando un caso agota los reintentos (`maxAttempts`, default 120) pasa a `CLASSIFICATION_FAILED`.
`CaseStatusService` declara la transición `CLASSIFICATION_FAILED → PENDING_CLASSIFICATION` como
válida, pero **nada la invoca** — no hay scheduler, no hay endpoint, no hay botón para el analista
ni para el asegurado. Es un estado terminal de hecho, aunque el modelo de estados diga que no
debería serlo.

Mientras tanto, `estado.ts` le muestra al asegurado:

```ts
CLASSIFICATION_FAILED:
  'Estamos procesando tu caso. No hace falta que hagas nada por ahora.',
```

y la bandeja del analista dice "El equipo reintenta el análisis. No hace falta hacer nada." Los dos
mensajes son falsos: no hay ningún reintento en curso. **Verificado con datos reales de Railway**:
los expedientes #4 (Provincia) y #5 (BBVA) están en este estado ahora mismo, sin ninguna forma de
resolverlos salvo tocar la base a mano.

**Fix sugerido:** o el scheduler también barre `CLASSIFICATION_FAILED` (con backoff, para no
reintentar infinito lo que ya falló 120 veces), o se agrega un endpoint/botón de "reintentar
manualmente" para el analista, o como mínimo el copy dice la verdad ("no se pudo clasificar
automáticamente, un analista lo va a revisar a mano").

---

## 🟠 23. Las fechas con hora se muestran mal — 12hs de diferencia

**Archivos:** [`seguimiento.component.ts:171,180,185`](../arbiter-frontend/src/app/features/portal/seguimiento/seguimiento.component.ts), [`status-timeline.component.ts:139`](../arbiter-frontend/src/app/shared/ui/status-timeline/status-timeline.component.ts), [`expediente-detail.component.ts:161`](../arbiter-frontend/src/app/features/expedientes/expediente-detail/expediente-detail.component.ts)

Los cinco lugares que muestran fecha+hora hacen lo mismo:

```ts
return d?.eventDate ? new Date(d.eventDate).toLocaleString('es-AR') : '—';
```

**Verificado en vivo con JavaScript en el navegador real de testeo:** un siniestro con
`eventDate: "2026-08-01T19:30:00"` se mostraba como **"1/8/2026, 07:30:00"** en el seguimiento del
asegurado — 12 horas de diferencia con el valor real.

```js
new Date("2026-08-01T19:30:00").toLocaleString('es-AR')            // → "1/8/2026, 07:30:00"  ← mal
new Date("2026-08-01T19:30:00").toLocaleString('es-AR', {hour12:false}) // → "1/8/2026, 19:30:00"  ← bien
new Date("2026-08-01T19:30:00").getHours()                          // → 19 (el dato en sí está bien)
```

La causa: `toLocaleString('es-AR')` sin opciones explícitas, en el motor ICU de este entorno de
testeo (Electron/Chromium), usa formato 12hs pero **pierde el "a. m./p. m."** — no es que la hora
esté mal calculada (`getHours()` da 19 correcto), es un problema puramente de formato de
presentación. Para un siniestro, la hora del hecho puede ser relevante para el análisis (consistencia
del relato, franjas horarias de riesgo), así que no es solo cosmético.

Es dependiente del navegador/locale — vale la pena confirmar si reproduce en el Chrome/Edge real
que va a usar el usuario final, pero como el código no fuerza el formato, el riesgo queda latente
en cualquier build donde el ICU tenga este comportamiento (y ya se demostró que existe al menos
uno).

**Fix sugerido:** pasar `{ hour12: false }` explícito en los 5 lugares, o mejor, centralizar el
formateo en un `DatePipe` de Angular (`| date:'dd/MM/yyyy, HH:mm:ss'`) para no repetir la llamada a
`toLocaleString` cinco veces con la misma opción.

---

## 🟡 24. "Fecha y hora de ocurrencia" nunca muestra la hora

**Archivo:** [`expediente-detail.component.ts:162`](../arbiter-frontend/src/app/features/expedientes/expediente-detail/expediente-detail.component.ts)

Bug puntual, distinto del ítem 23 (aunque en el mismo archivo):

```ts
{ label: 'Fecha y hora de ocurrencia', value: d?.eventDate ? new Date(d.eventDate).toLocaleDateString('es-AR') : null },
```

El label promete "hora", pero el código usa `toLocaleDateString` (solo fecha), no `toLocaleString`
(fecha+hora). El analista nunca ve a qué hora ocurrió el hecho denunciado, aunque el dato sí está
disponible (`eventDate` incluye la hora, capturada desde el wizard).

**Fix sugerido:** cambiar a `toLocaleString` (con la opción `hour12: false` del ítem 23 de paso).

---

## 🟠 25. La declaración PEP se pide y se persiste, pero el analista no la ve nunca

**Archivos:** [`nueva-denuncia.component.ts:158,267`](../arbiter-frontend/src/app/features/expedientes/nueva-denuncia/nueva-denuncia.component.ts), [`CaseReferenceResolver.java:59`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/CaseReferenceResolver.java), [`expediente-detail.component.ts:168`](../arbiter-frontend/src/app/features/expedientes/expediente-detail/expediente-detail.component.ts)

El wizard pide la declaración obligatoria (normativa UIF/PLA):

```html
<app-checkbox [(checked)]="pep">¿Sos Persona Políticamente Expuesta (PEP)?</app-checkbox>
<span class="pep-note">Declaración obligatoria según normativa UIF/PLA.</span>
```

y sí la manda y la persiste — no es el mismo patrón que el ítem 2 (que se descartaba en silencio):

```java
insured.setPep(Boolean.TRUE.equals(request.pep()));
```

Pero queda guardada en `Insured.pep`, no en el `Case`, y **`CaseResponse` nunca la expone**. El
frontend, en la grilla que ve el analista, la tiene directamente hardcodeada:

```ts
{ label: 'PEP (declarativo)', value: null },
```

Resultado: el dato se pide, se guarda, y **nadie puede verlo jamás** — ni el analista en el detalle
del expediente, ni por ninguna respuesta de API. Para un campo que existe específicamente por una
exigencia regulatoria (UIF/PLA), que quede invisible es peor que no pedirlo: da la falsa sensación
de que el control existe.

**Fix sugerido:** sumar `pep` (o `insuredPep`) a `CaseResponse` y bindearlo en
`expediente-detail.component.ts` en vez del `null` hardcodeado.

---

## 🟢 26. Deuda de documentación menor (comentarios desactualizados, import muerto)

Encontrados en el testeo del 4/8 tarde, sin impacto funcional, pero conviene limpiarlos:

- **`BandejaComponent`** importa `TableComponent` sin usarlo en el template (warning de build
  `NG8113: TableComponent is not used within the template of BandejaComponent`) — probablemente
  residuo del fix del ítem 12. Import muerto, borrar.
- **`AuthSessionService`** (comentario) dice que el token en memoria es "paso transitorio hasta
  integrar Auth0" — Auth0 ya está integrado y funcionando (CLAUDE.md, decisión #8). La sesión en
  memoria sigue siendo así a propósito (criterio de aceptación real de H0001, no un placeholder),
  solo el comentario quedó viejo.
- **`CaseController.getCase`** (javadoc) dice *"there's no owner check yet ... this only gates
  'logged in', not 'yours'"* — **desactualizado y falso hoy**: `CaseAccessPolicy.assertCanRead()`
  sí lo verifica (`callerDni.equals(caseRecord.getInsured().getDni())` para el rol `ASEGURADO`).
  Verificado en vivo con dos cuentas reales (Julián no puede ver el caso #1 de Martina, 404). No es
  una vulnerabilidad — solo hace falta actualizar el comentario para que no confunda a quien lo lea
  después.

**Fix sugerido:** un pase rápido de limpieza, sin apuro (no bloquea nada).

---

## 🟢 27. Seguridad RBAC/IDOR — todo verificado OK (sin hallazgos, queda documentado)

Chequeos hechos en vivo contra Railway el 4/8 tarde, vía API directa con tokens reales:

| Chequeo | Resultado |
|---|---|
| Asegurado (`asegurado.arbiter@gmail.com`) intenta `POST /cases/1/decision` | `403` ✅ |
| Analista BBVA pide `GET /cases/2?aseguradora=provincia` (caso ajeno) | `404` ✅ (no confirma ni niega que el caso exista) |
| Analista BBVA intenta decidir sobre su propio caso #2 ya `Rechazado` (estado terminal) | `409` ✅ (conflicto de máquina de estados, no fuga cross-tenant) |
| Request sin token a `GET /cases` | `401` ✅ |
| Decisión + justificación + `analyst_id` resueltos del JWT y persistidos | correcto, reverificado también para un analista de Provincia (no solo BBVA) |

Se documenta para que quede registro de que se revisó y no como una alarma — nada para arreglar acá.

---

## Resumen para priorizar

**Cerrados (14 de 21 ítems originales + el nuevo #22 sigue abierto — ver tabla de abiertos más abajo).**
Todos verificados en vivo, no solo con tests, en al menos una de las dos pasadas del 4/8.

| # | Ítem | Severidad | Cerrado en |
|---|------|-----------|------------|
| 1 | `analystId` hardcodeado rompe aprobar/rechazar | 🔴 Alta | commit `8a10d8d` |
| 2 | La justificación del analista se descarta | 🔴 Alta | commit `8a10d8d` |
| 3 | El wizard envía denuncias que el backend rechaza | 🔴 Alta | commit `8a10d8d` |
| 4 | Ningún campo tiene nombre accesible | 🔴 Alta | commit `8a10d8d` |
| 5 | URL inexistente = pantalla en blanco | 🟠 Media | commit `a238a61` |
| 6 | La sesión vence y nadie se entera | 🟠 Media | commit `a238a61` |
| 7 | "Guardado automático" no existe | 🟠 Media | commit `a238a61` |
| 11 | El seguimiento retrocede tras subir documentación | 🟠 Media | pase previo al 4/8 |
| 12 | La tabla de la bandeja desborda en móvil | 🟠 Media | commit `a238a61` |
| 15 | Sin validación de archivos | 🟡 Baja | commit `a238a61` |
| 16 | Fecha de siniestro futura | 🟡 Baja | commit `a238a61` |
| 18 | `/styleguide` pública | 🟡 Baja | commit `a238a61` |
| 19 | Favicon viejo | 🟡 Baja | commit `a238a61` |
| 21 | No se puede avanzar en el wizard | 🟡 Baja | commit `a238a61` |

**Abiertos** (los viejos re-verificados el 4/8 tarde + los 4 nuevos de esa misma pasada):

| # | Ítem | Severidad | Esfuerzo | Estado |
|---|------|-----------|----------|--------|
| 22 | `CLASSIFICATION_FAILED` sin salida + mensaje falso | 🔴 Alta — casos reales varados para siempre | Bajo-Medio | **Nuevo** |
| 8 | El referente tiene una sola pantalla | 🟠 Media — rol sin navegación | Alto | Re-verificado |
| 9 | `reports-service` inalcanzable desde el front | 🟠 Media — módulo entero sin consumir | Alto | Re-verificado |
| 10 | Expedientes asignados (lo toma Flor) | 🟠 Media | — | Re-verificado |
| 23 | Fechas con hora muestran 12hs de diferencia | 🟠 Media — dato con peso legal en un siniestro | Bajo | **Nuevo** |
| 25 | PEP se persiste pero no se ve nunca | 🟠 Media — compliance UIF/PLA invisible | Bajo | **Nuevo** |
| 13 | Tres pantallas sin breakpoint | 🟡 Baja (reevaluado: menos grave de lo que parecía) | Bajo | Re-verificado |
| 14 | Notificaciones sin cablear | 🟡 Baja — stub conocido | Alto (backend) | Re-verificado |
| 17 | Faltan estados vacíos | 🟡 Baja | Bajo | Re-verificado |
| 20 | "Sin datos" ambiguo | 🟡 Baja | Bajo | Re-verificado |
| 24 | "Fecha y hora de ocurrencia" no muestra hora | 🟡 Baja | Muy bajo | **Nuevo** |
| 26 | Deuda de documentación (import muerto, comentarios viejos) | 🟢 Cosmética | Muy bajo | **Nuevo** |
| 27 | RBAC/IDOR — sin hallazgos, documentado | 🟢 — | — | **Nuevo (verificación)** |
