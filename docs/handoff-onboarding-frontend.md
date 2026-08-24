# Handoff: Pantalla de bienvenida (onboarding) del asegurado — Frontend

**Branch backend:** `feature/onboarding-asegurado` (sobre `develop`)
**Para:** Flor
**Fecha:** 2026-08-24

---

## Contexto

Cuando un asegurado entra por primera vez después de activar su cuenta, tiene que pasar por un
paso de bienvenida donde completa sus datos de contacto y da su consentimiento de análisis
forense de imágenes (H0009). Es una sola vez — al siguiente login va directo al portal.

PEP **no se pregunta**: viene de la data de la aseguradora (póliza/KYC), ya está en `Insured.pep`.

---

## Qué ya está hecho en el backend

### Nuevos campos en `Insured` (common-lib)

| Campo | Tipo | Qué es |
|-------|------|--------|
| `imageConsentVersion` | `String` | Versión del texto que aceptó (ej. `"1.0"`) |
| `imageConsentAt` | `Instant` | Cuándo dio/revocó el consentimiento |
| `onboardingComplete` | `boolean` | Si completó el primer ingreso |
| `onboardingCompletedAt` | `Instant` | Cuándo lo completó |

### JWT: nuevo claim `onboardingComplete`

El login ya devuelve `onboardingComplete: boolean | null` tanto en el JWT como en `LoginResponse`.
Para ASEGURADO es `true`/`false`; para otros roles es `null`.

### LoginResponse (auth-service)

Se agregó el campo `onboardingComplete: Boolean` al final del record.

### Nuevos endpoints (auth-service, todos requieren JWT con rol `ASEGURADO`)

#### `GET /api/v1/auth/profile`
Devuelve el perfil completo del asegurado autenticado.

**Response:**
```json
{
  "name": "Martina",
  "surname": "Soteras",
  "dni": "42.987.654",
  "email": "asegurado@gmail.com",
  "phone": "+54 9 11 5555-0001",
  "pep": false,
  "imageConsent": false,
  "imageConsentVersion": null,
  "imageConsentAt": null,
  "onboardingComplete": false
}
```

#### `POST /api/v1/auth/profile/onboarding`
Completa el onboarding. Solo se puede llamar una vez (si `onboardingComplete` ya es `true`, devuelve 409).

**Request:**
```json
{
  "email": "nuevo@email.com",
  "phone": "+54 9 11 1234-5678",
  "imageConsent": true,
  "imageConsentVersion": "1.0"
}
```

**Response:** Un `LoginResponse` nuevo con el JWT actualizado (para que el frontend lo reemplace sin re-login).

#### `PATCH /api/v1/auth/profile`
Actualización parcial del perfil. Solo se mandan los campos que cambian. Para la página de "Mi perfil".

**Request (parcial, solo lo que cambia):**
```json
{
  "email": "otro@email.com",
  "imageConsent": false,
  "imageConsentVersion": "1.0"
}
```

**Response:** `LoginResponse` con JWT nuevo.

### Endpoint de pólizas (ya existente, cases-service)

#### `GET /api/v1/policies?insuredId={dni}`
Devuelve las pólizas del asegurado. Requiere JWT con rol `ASEGURADO`. Ya existe y funciona.

**Response:** `PolicyResponse[]` con `policyNumber`, `insurerName`, `branch`, `insuredItem`,
`product`, `effectiveFrom`, `effectiveTo`, `insuredAmount`, `coverages[]`, etc.

Esto es lo que la pantalla de bienvenida puede mostrar para que el asegurado vea "de qué
aseguradora sos y qué pólizas tenés". No hace falta un endpoint nuevo.

---

## Qué hay que hacer en el frontend

### 1. `AuthSession` — agregar `onboardingComplete`

En `auth-session.service.ts`, agregar `onboardingComplete: boolean | null` a la interfaz `AuthSession`.

### 2. Guard de onboarding

Crear un guard (`onboarding.guard.ts`) que:
- Si el usuario es `ASEGURADO` y `onboardingComplete === false` → redirige a `/portal/onboarding`.
- Si es `ASEGURADO` y `onboardingComplete === true` → pasa.
- Si no es `ASEGURADO` → pasa (no aplica).

Aplicar este guard a las rutas del portal (`/portal/*`) excepto `/portal/onboarding` (sería
circular). El guard actual `roleGuard` se mantiene — este se agrega encima.

### 3. Pantalla de bienvenida (`/portal/onboarding`)

Un componente standalone con:

1. **Bienvenida** — "Hola, {nombre}. Antes de empezar, completá tus datos."
2. **Tus pólizas** (solo lectura) — Llamar a `GET /api/v1/policies?insuredId={dni}` y mostrar
   una card por póliza con: aseguradora, ramo, producto, bien asegurado, vigencia. Es para que
   el asegurado vea qué tiene. No se edita nada acá.
3. **Datos de contacto** — Email y teléfono. Precargar con `GET /api/v1/auth/profile`. Editables.
4. **Consentimiento de imágenes (H0009)** — Checkbox con el texto legal completo (ya existe en el
   wizard, reutilizar). Versión: `"1.0"` (hardcodeada por ahora).
5. **Botón "Continuar"** — `POST /api/v1/auth/profile/onboarding`. En el response viene un JWT
   nuevo: reemplazar la sesión con `authSessionService.start(response)` y redirigir a
   `/portal/home`.

**Abandonar a la mitad:** si el asegurado cierra el browser sin completar, al siguiente login el
JWT sigue con `onboardingComplete: false` y el guard lo manda de vuelta acá. No hace falta
guardar progreso parcial (son 3 campos).

### 4. Sacar el consentimiento y PEP del wizard de denuncia

En `nueva-denuncia.component.ts` y `.html`:
- **Sacar el checkbox de consentimiento de imágenes** (líneas ~286-295 del HTML, signal `imageConsent`).
- **`pep` ya se manda `false`** hardcodeado con un comment — no cambia por ahora, eventualmente
  se saca del request.
- `imageConsent` en el submit: mandar `null` o sacarlo (el backend lo ignora).

### 5. Página de perfil (para cambiar consentimientos después)

Un componente nuevo accesible desde el menú del usuario (el dropdown de `app.ts`). Usa:
- `GET /api/v1/auth/profile` para cargar.
- `PATCH /api/v1/auth/profile` para guardar cambios.
- Reemplazar la sesión con el JWT nuevo del response.

Mostrar: nombre y DNI (solo lectura), email y teléfono (editables), PEP (solo lectura, viene de
la aseguradora), consentimiento de imágenes (editable, con el texto legal).

### 6. Notas de diseño

- Usar el design system existente: `app-card`, `app-input`, `app-button`, `app-checkbox`, tokens
  semánticos. No inventar primitivas.
- La pantalla de bienvenida debería sentirse como un paso intermedio limpio, no como un formulario
  burocrático. Cards con info de las pólizas arriba, formulario corto abajo.
- Responsive (requisito de usabilidad: ≥85% éxito en mobile).

---

## Flujo completo

```
Referente invita asegurado (ya funciona, pero solo ANALISTA — falta habilitar ASEGURADO)
  → Mail con link de activación
  → Asegurado elige contraseña (ActivateAccountComponent, ya funciona)
  → Login
  → JWT tiene onboardingComplete: false
  → Guard redirige a /portal/onboarding
  → Asegurado ve sus pólizas, completa contacto, da consentimiento
  → POST /api/v1/auth/profile/onboarding
  → JWT nuevo con onboardingComplete: true → sesión se reemplaza
  → Redirect a /portal/home
  → Siguiente login: JWT ya tiene onboardingComplete: true → directo al portal
```
