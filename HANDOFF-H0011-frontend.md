# Handoff — H0011 "Búsqueda y filtrado de expedientes", parte Frontend

> Archivo temporal para arrancar esta parte de la historia. Borralo una vez que lo hayas
> incorporado a tu propio contexto de trabajo — no es documentación permanente del proyecto.

Repo: `F:\Biblioteca\Universidad\UTN\Librería\Quinto Año\Proyecto Final K5054\arbiter`
Rama sugerida: arrancá desde `develop` (ahí está todo lo que se describe acá, ya mergeado).

## Resumen para no perderse

El Backend de esta historia (H0011) está **cerrado y verificado**: 158 tests en verde, probado
contra Docker real. El **Frontend es lo que falta** — y hasta ahora estaba bloqueado porque la capa
de servicio Angular (`ExpedienteService`) no exponía la mayoría de los filtros que el backend ya
soporta. Eso ya está resuelto (ver más abajo) — arrancás con el service listo, sin nada que cablear
del lado del contrato HTTP.

## El endpoint (backend, ya cerrado, no tocar)

```
GET /api/v1/cases
```

Protegido con JWT (`isAuthenticated()`, cualquier rol logueado). Todos los params son opcionales y
combinables:

| Param | Tipo | Nota |
|---|---|---|
| `status` | `CaseStatus` | `PENDING_CLASSIFICATION`, `PENDING_ANALYST_REVIEW`, `CLASSIFICATION_FAILED`, `AWAITING_DOCUMENTATION`, `APPROVED`, `REJECTED` |
| `claimCause` | `string` | tipo de siniestro, match exacto |
| `policyNumber` | `string` | match exacto |
| `insuredId` | `string` | match exacto (DNI/identificador, no nombre — ver Gap 2 abajo) |
| `eventDateFrom` / `eventDateTo` | ISO `yyyy-MM-dd` | filtra por **fecha del hecho**, no por fecha de denuncia (son campos distintos en el dominio — ver Gap 3) |
| `page`, `size`, `sort` | paginación Spring Data | default `page=0&size=20&sort=id,desc`. Ejemplo: `sort=eventDate,desc` |

Respuesta paginada: `{ content: CaseResponse[], totalElements, totalPages, number, size }`.

## Lo que ya está listo del lado Angular (no hace falta que lo toques)

**`arbiter-frontend/src/app/features/expedientes/expediente.service.ts`** — recién actualizado:

```ts
export interface ExpedienteListParams {
  status?: string;
  claimCause?: string;
  policyNumber?: string;
  insuredId?: string;
  eventDateFrom?: string;  // ISO yyyy-MM-dd
  eventDateTo?: string;
  page?: number;
  size?: number;
  sort?: string;           // ej. "eventDate,desc"
}

list(params: ExpedienteListParams = {}): Observable<PagedResponse<ExpedienteResponse>>
```

Ya expone **los 5 filtros + sort + paginación**, todos opcionales — llamalo con lo que necesites,
por ejemplo:

```ts
this.expedienteService.list({ status: 'PENDING_ANALYST_REVIEW', claimCause: 'Robo en vía pública', page: 0, size: 20 })
```

`bandeja.component.ts` y `mis-expedientes.component.ts` ya están actualizados a esta firma nueva
(por si los mirás como referencia de uso — ojo que ellos piden `size: 100` como parche temporal
hasta que tengas paginación real armada, no lo copies literal a tu UI final).

## Lo que falta construir (esto sí es tuyo)

Según la card de Trello (Frontend) y el wireframe:

1. **Panel de filtros** (barra lateral o superior): selects/inputs para `status`, `claimCause`,
   rango de fechas (`eventDateFrom`/`eventDateTo`), `policyNumber`. Usá el kit del design system
   (`app-input`, `app-button`, etc. — ver `CLAUDE.md`, sección "Design System del frontend", y
   `/styleguide` para ver todo lo disponible antes de armar algo nuevo).
2. **Paginación real**: controles de página + tamaño, conectados a `page`/`size`/`totalPages` de
   la respuesta. Hoy `bandeja`/`mis-expedientes` piden `size=100` como parche — cuando metas
   paginación real ahí, sacá ese parche.
3. **Ordenamiento de columnas**: mapea a `sort` (`campo,asc|desc`).

## Dos gaps reales que NO podés resolver solo del lado frontend — hay que decidirlos con el equipo

### Gap 1 — Búsqueda de texto libre

El HU oficial pide poder buscar por número de expediente/póliza/asegurado con un único campo de
texto libre. **El backend no tiene ningún parámetro para esto.** No lo armes como un filtro
client-side sobre lo ya cargado — como el backend pagina (`size` default 20), un filtro así solo
buscaría dentro de la página actual, no en todos los expedientes. Antes de construir esto, avisá:
necesita un parámetro nuevo en `GET /api/v1/cases` (backend) o alguna otra solución acordada.

### Gap 2 — "Nombre del asegurado"

El HU pide que la búsqueda incluya el nombre del asegurado. `Case` (la entidad de `cases-service`)
**no tiene ningún campo de nombre** — solo `insuredId`, que es un identificador tipo DNI
(`42.987.654`), no un nombre. Ni agregando el parámetro de búsqueda se podría cumplir esto tal cual
está redactado, salvo que se sume ese campo al modelo. Otro punto para el equipo, no algo que
puedas resolver vos sola en el frontend.

### Gap 3 — Fecha de denuncia vs. fecha del hecho (menor, pero real)

El HU dice "rango de fecha de **denuncia**"; el backend filtra por `eventDate` (fecha del **hecho**
narrado en la denuncia, no cuándo se cargó el registro). Son conceptos distintos en el vocabulario
del proyecto (`Denuncia` ≠ `Siniestro`, ver `CLAUDE.md`). Si armás un date-picker con la etiqueta
"Fecha de denuncia", ojo que está filtrando por otra cosa — usá una etiqueta que no genere
confusión (ej. "Fecha del hecho") hasta que el equipo decida si hace falta un campo nuevo.

## Cómo levantar todo para probar

- **JDK 21 explícito** si tocás backend: `JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"` — el JDK
  default de esta máquina (26) rompe Lombok.
- **Docker real**: `docker compose up -d` desde la raíz (contexto siempre ahí, no en el módulo).
  Si `postgres`/`ollama` quedan "Exited" mientras los servicios de aplicación están "Up", corré
  `docker compose restart auth-service cases-service classification-service` después de que
  levanten, para que reconecten limpio.
- **Reseed de datos**: `docker exec -i arbiter-postgres-1 psql -U arbiter -d arbiter < db/init.sql`
  — **siempre por Bash, nunca PowerShell con `Get-Content | ...`** (rompe encoding UTF-8, mangla
  tildes). Deja 5 cases fijos (ids 1-5) + 3 usuarios de prueba
  (`analista@arbiter.test`/`asegurado@arbiter.test`/`referente@arbiter.test`, todos password
  `changeme123`).
- **Frontend**: `cd arbiter-frontend && npm start` (`ng serve`, puerto **4200** — el 5173 que dice
  `CLAUDE.md` está desactualizado).
- **Verificación**: probé el flujo completo recién — login como analista, bandeja trayendo
  expedientes reales vía `GET /api/v1/cases?page=0&size=100`, sin errores de consola. Los filtros
  combinados (`claimCause` + rango de fechas + `sort`) los probé por API directa y funcionan.

## Dónde hay más contexto si hace falta

- `docs/siniestros/GAPS-FLUJO.md` — gaps conocidos del flujo completo, incluido el filtro por
  nivel de riesgo (Gap G) que tampoco está en el backend todavía.
- `docs/DER.md` — modelo de datos actual, para confirmar qué campos existen de verdad en `Case`
  antes de asumir que algo está disponible.
- `docs/postman/Arbiter_Cases_EndToEnd.postman_collection.json` — ejemplos de los filtros del
  backend corriendo contra `db/init.sql` (los requests con prefijo `8️⃣`).
