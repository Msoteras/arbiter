# Cache del frontend — mapa de decisiones

Resultado de la sesión explore-unknowns del 12/10/2026. **Implementado el 14/10/2026** en
`feature/onboarding-aseguradora` (Valentina pidió un único PR junto con la puesta en marcha, en lugar
de la rama aparte de D13).

**Estado:** cerrado. L1 = opción A; L2 y L5 con el arreglo propuesto (confirmados por Valentina el
14/10). La tabla del paso 5 está abajo y vive en código en `core/http/cache-invalidation.interceptor.ts`.

## Problema

Cada pantalla pide sus datos en el constructor y los guarda en un signal del componente
(`features/admin/users/users.component.ts:93-131`). Al cambiar de ruta, Angular destruye el
componente y los datos se pierden. Los services devuelven el `http.get` directo, sin estado
(`core/auth/user-admin.service.ts:46`). Volver a una pantalla es esperar la carga completa otra vez.

## Cuadrante 1 · Lo que ya sabíamos

- ~25 endpoints GET en ~20 services. El único cache es `shareReplay` sobre un JSON estático
  (`core/services/argentina-locations.service.ts:17-20`).
- Patrón de "store" en un service que ya existe: `CaseMessagesService` (`features/cases/case-messages.service.ts:22-34`)
  y `NotificationsService` (`core/notifications/notifications.service.ts:32-38`).
- `messages` mantiene la pantalla montada con un hijo sin componente (`app.routes.ts`).
- Reportes recuerda la solapa pero no los filtros, a propósito (`features/admin/reports/reports-tab-memory.ts`).
- Logout limpia la sesión y las notificaciones en `app.ts`. El 401 limpia solo la sesión (`core/http/auth.interceptor.ts:26`).
- Sin librería de cache: Angular 20.3 y rxjs 7.8.
- Railway: un servicio dormido tarda 5-7 s. Un pedido rechazado por la red privada **igual lo despierta**,
  pero no trae datos (`docs/despliegue-railway.md:386-390`). `cases-service` no duerme (`:440`).
  `upstreamRetryInterceptor` reintenta ~20 s y muestra un toast (`core/http/upstream-retry.interceptor.ts`).

## Decisiones

| # | Tema | Decisión | Por qué |
|---|---|---|---|
| D1 | Mecanismo | Helper propio en `core/`, sin librería (~60-80 líneas). Guarda por clave en signals, `invalidate(key)`, vaciado total. | Cubre la necesidad en un archivo chico. Sigue el patrón de `CaseMessagesService`. Evita `@tanstack/angular-query-experimental`. `httpResource` no sirve solo: se destruye con el componente. |
| D2 | Volver a una pantalla | Muestra lo guardado al instante y refresca en segundo plano. Los cambios se aplican solos, salvo en pantallas con **Actualizar** (D5). | Rápido para el usuario. |
| D3 | Prefetch: cuándo | Al terminar de cargar el inicio, con **reintentos silenciosos** (mismos tiempos, sin toast, marcados con un `HttpContextToken` que lee el interceptor). Nunca Tablero ni Reportes. | Despierta Railway mientras el usuario mira el inicio. Reportes escribe en `metrics_day`/`metrics_daily_*` al pedirse y es pesado. |
| D4 | Prefetch: qué | **Referente:** Usuarios, Liquidaciones (pendientes y autorizadas), ramos, resumen de coberturas, Proveedores, configs de fraude, scoring, atribuciones y plazo objetivo. **Analista:** primera página de la Bandeja sin filtros + hechos generadores, carga por analista, resumen de lentes y analistas. **Asegurado:** Mis expedientes, Mis pólizas (sirve también para el paso 1 de Nueva denuncia) y Perfil. | Listas livianas y muy probables. Afuera: detalle de Reglas (1 pedido por cobertura, `rules.component.ts:693,728`), pasos 2-4 de la denuncia, detalle de expediente. Mensajes ya se pide al entrar (`app.ts:93`). |
| D5 | Botón Actualizar | Solo en **Bandeja** y **Liquidaciones pendientes**. Aparece solo cuando el refresco trae cambios. `app-button` `secondary` `sm`. | Otras personas las cambian seguido y se hace clic en las filas. |
| D6 | ¿Sobrevive un F5? | No. Solo en memoria. | Sin datos personales en el navegador. Sin datos con forma vieja después de un deploy. El prefetch recarga. |
| D7 | Invalidación | Cada acción invalida las claves que afecta. Se vuelven a pedir al toque y se aplican directo, **sin** Actualizar. Tabla "acción → claves" a revisar en el paso 5. | Evita "lo acabo de aprobar y sigue pendiente". Ej.: `decide()` → Bandeja + resumen del analista; alta de analista → lista de analistas de la Bandeja. |
| D8 | Indicador mientras refresca | Ninguno. | Un indicador de ~1 s en cada vuelta parpadea y dice "esperá". |
| D9 | Toast "El sistema se está iniciando…" | Solo en pedidos que dispara el usuario, nunca en el prefetch. | — |
| D10 | Documentación | Sección "Cache del frontend" en CLAUDE.md: el patrón y la regla de invalidación. | El grupo entero va a sumar pantallas. |
| D11 | Dónde se nota | Railway (demo y defensa). | Arranque en frío. |
| D12 | Terminado | Tests unitarios del helper (Karma), pantallas de D4 migradas, `npm run lint` en 0, prueba en el navegador contra Railway (entrar, Usuarios, cambiar de pantalla, volver). | — |
| D13 | Rama | Rama nueva desde `feature/onboarding-aseguradora` cuando esté terminada. Nada de código antes. | Onboarding modifica las configs que entran en D4. |

Preferencias que mostró Valentina (eligió no delegar respuestas): velocidad percibida primero sin datos
engañosos; simple y propio antes que dependencias; lo que escribe en la base o pesa no se dispara solo;
lo que hace el propio usuario se ve en el momento; nada de datos personales persistidos en el navegador.

## Cuadrante 4 · Hallazgos

### 🔴 L1 · El refresco puede pisar un borrador sin guardar — **CERRADA: opción A**
Las configs guardan borrador y comparan contra lo guardado (`fraud-config.component.ts:45-53,96-97`,
`scoring-config.component.ts:47-66`, `settlement-authorities-config.component.ts:47-53`,
`rules.component.ts:513-514`). Con un servicio dormido, el refresco llega hasta ~7 s después y puede
pisar lo que el referente ya empezó a editar.

Opciones:
- **A (recomendada)** Con cambios sin guardar (`dirty()`), el refresco no toca la pantalla. El dato queda en el cache y se aplica al guardar o descartar.
- **B** El refresco nunca aplica a una pantalla editable.
- **C** Se aplica siempre y se acepta el riesgo.

### 🔴 L2 · El cache se vacía en el logout pero no cuando vence la sesión — **CERRADA: arreglo aplicado**
El 401 limpia la sesión pero no los datos (`auth.interceptor.ts:26`). La sesión arranca en
`login.component.ts:48`, `activate-account.component.ts:144` y `profile.service.ts:27,33`. Si vence y
entra otro usuario en la misma pestaña, vería datos del anterior.
**Arreglo:** el cache escucha `AuthSessionService.session` y se vacía cuando pasa a `null` o cambia el `id`.
Mantener la misma sesión (`profile.service` después del onboarding) no lo vacía.

### 🟡 L3 · Ids de expediente repetidos entre aseguradoras
`case-messages.service.ts:13-15`. Toda clave de expediente lleva la aseguradora (`case:<insurer>:<id>`).
Pesa sobre todo en la idea de la segunda tanda.

### 🟡 L4 · Filtros de la Bandeja en la URL
`inbox.component.ts:132,208,453`. La clave es la combinación completa de filtros y página. El prefetch
solo llena "sin filtros, página 1".

### 🟡 L5 · Pantallas que usan el objeto recibido sin copiar — **CERRADA: arreglo aplicado**
`fraud-config.component.ts:96` hace `this.rule.set(rule)` sin copia. Con cache, modificarlo modifica lo
guardado. **Arreglo:** en modo dev el helper congela lo que guarda (`Object.freeze` profundo) para que
una mutación tire error al probar.

### ⚪ Para tener en cuenta
- La bandeja de mensajes **no se migra**: `CaseMessagesService` + el polling de `app.ts:93-110` ya hacen de cache.
- `inbox.component.spec.ts` simula `CaseService` y hay que adaptarlo.
- La tabla de invalidación se arma sobre la versión final de las configs, después de onboarding.

## Segunda tanda (ideas, no decididas)
- Prefetch del detalle de expediente al pasar el mouse por una fila de la Bandeja (ver L3).

## Paso 5 · Acción → claves invalidadas

| Escritura (URL) | Invalida | Ejemplos |
|---|---|---|
| `/auth/users…` | `users:` | alta de analista, cambio de rol, baja, alta de asegurados |
| `/auth/profile…` | `insured:profile` | el asegurado edita su perfil o termina el onboarding |
| `/cases…` salvo `/messages` | `cases:` | decisión, asignar, reabrir, liquidación, denuncia nueva, adjuntos |
| `/rules/branches…` | `rules:branches`, `rules:coverage-summary` | alta, renombre o baja de ramo |
| `/coverages…` | `rules:coverage-summary` | alta o baja de cobertura |
| `/expert-firms…` | `rules:providers` | proveedores |
| `/settlement-authorities…` | `rules:authorities` | topes por ramo |
| `/rules/fraud-record-rule` | `rules:fraud` | gestión de fraude |
| `/rules/scoring` | `rules:scoring` | puntaje de riesgo |
| `/rules/resolution-target` | `rules:target` | objetivo de resolución |

`cases:` es grueso a propósito: una escritura sobre un expediente cambia listas, conteos y resúmenes a
la vez. Lo invalidado que se leyó en los últimos 5 minutos se vuelve a pedir enseguida; el resto se
pide en la próxima visita.

## Decisiones tomadas al implementar

- La carga por analista no se precarga para el analista: el endpoint es solo del referente.
- Reglas guarda el refresco de la lista de ramos sin aplicarlo: aplicarlo vuelve a seleccionar un ramo.
- Se precargan las dos listas de pólizas del asegurado (con y sin vencidas): "Mis pólizas" y el paso 1
  de la denuncia piden listas distintas.

## Para retomar
Implementado. La validación está en `docs/validacion-cache-frontend.md`.
