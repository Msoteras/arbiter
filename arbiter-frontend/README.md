# arbiter-frontend

SPA Angular 20 (standalone components, signals, `ChangeDetectionStrategy.OnPush`) del sistema de
gestión de siniestros Arbiter. Sirve tres experiencias distintas según el rol del usuario
(`ANALISTA_SINIESTROS`, `REFERENTE_ASEGURADORA`, `ASEGURADO`) sobre la misma app.

Arquitectura, modelo de dominio y decisiones cerradas están documentadas en el
[`CLAUDE.md`](../CLAUDE.md) de la raíz. Este README es solo lo específico del frontend.

## Requisitos

- Node 20+
- Los backends corriendo (ver el [`Readme.md`](../Readme.md) de la raíz) — en dev, esta app no les
  pega directo: los rutea `proxy.conf.json` por path.

## Desarrollo local

```bash
npm install
npm start          # ng serve → http://localhost:4200
```

`npm start` corre con `--proxy-config proxy.conf.json` (ver `angular.json`), así que
`environment.apiBaseUrl` es siempre `/api/v1` relativo — nunca un host:puerto hardcodeado. El proxy
resuelve cada path al módulo backend que lo sirve:

| Path | Backend | Puerto |
|---|---|---|
| `/api/v1/auth` | `auth-service` | 8080 |
| `/api/v1/rules` | `rules-service` | 8081 |
| `/api/v1/claims` | `classification-service` | 8082 |
| `/api/v1/cases`, `/api/v1/notifications`, `/api/v1/policies`, `/api/v1/claim-causes`, `/api/v1/coverages`, `/api/v1/expert-firms` | `cases-service` | 8083 |

En producción el mismo ruteo por path lo hace Nginx (ver `docs/despliegue-railway.md`).

```bash
npm run build       # build de producción a dist/
npm run watch        # build en modo desarrollo con watch
npm test             # unit tests (Karma + Jasmine)
```

## Rutas principales

Cada rol aterriza en su propio home tras el login (`app.routes.ts`); `roleGuard` valida el rol
contra el JWT y `onboardingGuard`/`onboardingPendingGuard` fuerzan el flujo de alta del asegurado
(H0009) antes de dejarlo entrar al resto del portal.

| Rol | Home | Resto de sus pantallas |
|---|---|---|
| `ANALISTA_SINIESTROS` | `/home` | `/inbox` (bandeja), `/cases/:id` (detalle + decisión), `/insurer/dashboard`, `/insurer/reports` (solo lectura) |
| `REFERENTE_ASEGURADORA` | `/insurer/home` | `/insurer/users`, `/insurer/rules`, `/insurer/dashboard`, `/insurer/reports`, y `/inbox`/`/cases/:id` en modo solo lectura (sin asignar ni decidir) |
| `ASEGURADO` | `/portal/home` | `/portal/onboarding`, `/new-claim` (wizard de denuncia), `/portal` (mis expedientes), `/portal/cases/:id` (seguimiento), `/portal/cases/:id/documents`, `/portal/profile` |

`/styleguide` es la vitrina viva del design system (cualquier sesión autenticada puede entrar, no es
de un rol en particular) — ver la sección siguiente.

## Design System

El proyecto tiene un design system propio en `src/styles/` (`_tokens.scss` → primitivos,
`_semantic.scss` → roles que consumen los componentes, `_typography.scss` → clases `.t-*`) y un kit
de componentes en `src/app/shared/ui/` (`app-button`, `app-badge`, `app-card`, `app-input`,
`app-modal`, `app-table`, `app-fraud-gauge`, `app-status-timeline`, `app-doc-upload`, entre otros).

**Toda UI nueva tiene que usar este sistema** — no traer Tailwind, no valores hex/px crudos fuera de
`_tokens.scss`, no reimplementar botones/cards/inputs a mano. El detalle completo (qué capa consume
qué, reglas accionables, cuándo sumar algo a `/styleguide`) está en la sección "Design System del
frontend" de [`CLAUDE.md`](../CLAUDE.md).

## Estructura

```
src/app/
├── core/               # auth (JWT, guards), cliente http, modelos de dominio, notificaciones
├── shared/ui/          # kit de componentes del design system
├── features/
│   ├── auth/            # login, activar cuenta, recuperar contraseña
│   ├── home/            # un home por rol: analista-inicio, referente-inicio, asegurado-inicio
│   ├── expedientes/      # bandeja del analista, detalle de expediente, wizard de nueva denuncia
│   ├── portal/           # portal del asegurado: onboarding, mis expedientes, seguimiento, perfil
│   ├── admin/            # panel del referente: usuarios, reglas, dashboard, reportes
│   └── styleguide/       # vitrina del design system
├── app.routes.ts
└── app.config.ts
```
