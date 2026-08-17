# Handoff — Validaciones de póliza en el alta (gate de mora + hechos generadores por cobertura)

Documento para retomar mañana. Fecha: 2026-08-15. Branch: `feature/validaciones-polizas-reglas-core`.

> Antes de tocar nada, leé también:
> - `docs/caso-prueba-reglas-duras.md` — caso de prueba manual de reglas duras + Hard Stop + mora.
> - `db/README-multitenant.md` — cómo son los scripts de `db/` y el par canónico (`init-multitenant.sql` + `seed-demo.sql`).
> - El `CLAUDE.md` raíz.

---

## Estado al cortar: nada commiteado, todo compila, **nada probado en navegador**

Lo de esta sesión (después del último push, `cd9660f` → `b7c7bef`... en realidad el último commit
pusheado fue el de `db/seed-demo-extra.sql`) está **sin commitear**. Compila limpio (backend con
`mvn -am test-compile` sobre todos los módulos, frontend con `ng build`) pero **no lo vi correr**:
no tengo login para probar el wizard del asegurado. Antes de commitear, probar en serio.

```bash
git status --porcelain
```
va a mostrar (aprox):
- `cases-service/.../controllers/CaseController.java` (M)
- `cases-service/.../controllers/ClaimCauseController.java` (M)
- `cases-service/.../services/CaseService.java` (M)
- `cases-service/.../services/CaseServiceImpl.java` (M)
- `cases-service/.../services/PolicyEligibilityValidator.java` (M — cambió la firma de `validate`)
- `cases-service/.../services/RulesServiceClient.java` (M)
- `cases-service/.../dto/EligibilityCheckRequest.java` (nuevo)
- `cases-service/.../dto/EligibilityCheckResponse.java` (nuevo)
- `cases-service/src/test/.../PolicyEligibilityValidatorTest.java` (M — adaptado a la firma nueva)
- `arbiter-frontend/.../expedientes/expediente.service.ts` (M)
- `arbiter-frontend/.../expedientes/policy.service.ts` (M)
- `arbiter-frontend/.../expedientes/nueva-denuncia/nueva-denuncia.component.{ts,html}` (M)
- `arbiter-frontend/.../expedientes/case-documents/case-documents.component.html` (M)
- `arbiter-frontend/.../portal/seguimiento/seguimiento.component.{ts,html}` (M)

## Qué se hizo (dos features, mismo hilo de conversación)

### 1 · Gate de elegibilidad ANTES de completar la denuncia (lo pedido: "no me deje completar la
denuncia si las reglas dicen que no podés deber cuotas; si me lo permiten, que tire una advertencia")

Antes: el wizard solo chequeaba vigencia + coherencia de fechas del lado del cliente (a mano,
duplicando lógica), dejaba pasar carencia y mora sin chequear nada, y recién en el submit final
(paso 3, con los 4 documentos ya subidos) te enterabas si el backend rechazaba con 422.

Ahora:
- **Nuevo endpoint** `POST /api/v1/cases/eligibility` (rol ASEGURADO) — corre *exactamente* lo mismo
  que `POST /cases` corre antes de armar el `Case` (ownership, vigencia, carencia, mora vía
  `PolicyEligibilityValidator`), sin crear nada. Devuelve `{eligible, reason}`, nunca un error —
  "no podés denunciar esto" no es un error acá.
- `PolicyEligibilityValidator.validate` cambió de firma: tomaba `CaseRequest` completo, ahora toma
  solo `(policyNumber, eventDate, policeReportAt, coverage)` — lo único que en verdad lee. Esto
  permitió compartir la lógica entre `createCase` (que sí tiene el `CaseRequest` completo) y el
  nuevo `checkEligibility` (que solo tiene lo que el wizard ya cargó en el paso 2, sin
  branch/product/claimCause/descripción/etc.).
- **Frontend**: `nueva-denuncia.component.ts` llama a este endpoint (debounced 400ms) apenas hay
  póliza + fecha del hecho. Si `eligible=false`, bloquea "Siguiente" (mismo mecanismo que ya
  bloqueaba por vigencia) y muestra el motivo real del backend. Si `eligible=true` pero
  `!policy.upToDate` (mora en modo STANDBY, o la regla apagada), **no bloquea** — se deja el badge
  ya existente "Póliza con premios adeudados" como aviso no bloqueante. Falla ABIERTO si el propio
  chequeo no responde (no bloquea el wizard por un problema transitorio del precheck — el gate real
  en el submit sigue estando).

**Sin probar**: todo el flujo end-to-end. Ni siquiera confirmé que `POST /cases/eligibility` responde
bien contra el stack real — solo compila. Casos a probar a mano:
1. Póliza al día → sin aviso, "Siguiente" habilitado.
2. Póliza en mora, `onArrears=STANDBY` (o regla apagada) → badge de aviso, pero "Siguiente" igual
   habilitado.
3. Póliza en mora, `onArrears=REJECT` (configurarlo desde Hard Stop primero) → "Siguiente"
   deshabilitado, mensaje del backend visible, no debería dejar avanzar al paso 3.
4. Vigencia/carencia rotas (ya probadas en `docs/caso-prueba-reglas-duras.md` §6, pero ahora pasan
   por el endpoint nuevo en vez del check viejo a mano — reverificar que el mensaje siga siendo el
   mismo).
5. rules-service caído mientras se completa el wizard → no debería trabar nada (fail open).

### 2 · Hechos generadores filtrados por lo que la cobertura de la póliza realmente cubre

Antes: el selector "¿Qué te pasó?" (`GET /api/v1/claim-causes?branch=X`) mostraba **todos** los
hechos generadores del ramo, sin mirar la cobertura específica de la póliza elegida ni las
exclusiones (`COVERAGE_EXCLUSION`) que el referente haya configurado. Ejemplo real del seed: la
cobertura "Robo de celular" excluye "Hurto" — antes el wizard te dejaba elegir "Hurto" igual, y
recién en la clasificación (async, después de subir documentación) el motor lo detectaba.

- `ClaimCauseController.byBranch` suma un `policyNumber` opcional. Con él, resuelve la cobertura de
  esa póliza (mismo `CaseReferenceResolver` que usa el resto del alta) y descuenta los hechos
  generadores excluidos, leídos de rules-service (`RulesServiceClient.excludedClaimCauseIds`, nuevo
  método, reusa `/internal/evaluable?coverageId=X` que ya usa classification-service).
- Confirmado con el usuario: `COVERAGE_EXCLUSION` es lista **negra** (la cobertura cubre todo el
  ramo salvo lo excluido), no lista blanca — el filtro está armado así.
- Best-effort: si rules-service no responde o la póliza no está sincronizada localmente, cae a la
  lista sin filtrar (no rompe el selector) — mismo criterio que el resto de `CaseReferenceResolver`.
- **Frontend**: `PolicyService.listClaimCauses(branch, policyNumber?)` y `nueva-denuncia` ahora
  reaccionan a la póliza completa (branch + policyNumber), no solo al branch — dos pólizas del
  mismo ramo pueden tener coberturas con exclusiones distintas.

**Sin probar**: elegir la póliza `POL-CEL-2026-042` (cobertura "Robo de celular", branch Celulares)
y confirmar que "Hurto" **no** aparece en el selector — matchea contra `insurer_rule` id 3
(`COVERAGE_EXCLUSION` del seed, ver `db/init-multitenant.sql` línea ~737).

## Otros arreglos sueltos de esta sesión (chicos, probablemente OK pero tampoco vistos en navegador)

- **Spinner de "Cargando tu siniestro…"** en `seguimiento.component.html`: estaba en un
  `app-card` sin spinner real, ahora usa `app-inline-loading`.
- **"Todavía no enviaste documentación"** (`case-documents.component.html`): sacado el "todavía" en
  los dos lugares donde aparece — sonaba a pendiente aunque el expediente ya estuviera rechazado. El
  componente no sabe el estado del caso, así que se optó por wording neutral en vez de enchufarle el
  estado (que hubiese sido un cambio más grande a un componente genérico).

## Cerrado después de escribir este handoff (antes de que se probara en navegador)

- **Test de `checkEligibility`** sumado a `CaseServiceImplTest` (unit, mocks — no Testcontainers):
  4 casos — elige bien, `PolicyNotEligibleException` → `eligible=false`, póliza de otro asegurado →
  `eligible=false` sin tocar `PolicyEligibilityValidator`, y DNI ajeno → sigue tirando
  `InsuredIdentityMismatchException` (no se traga esa, a diferencia de las otras dos). Solo
  `test-compile`, no lo corrí — sigue siendo terreno del usuario.
- **`docs/caso-prueba-reglas-duras.md` §11** agregado: caso de prueba del precheck (Diego Sosa +
  `onArrears=REJECT`, bloqueo en paso 2 sin llegar a subir documentación) y del recorte de hechos
  generadores por cobertura (Hurto no debería aparecer con `POL-CEL-2026-042`).

## Antes de commitear

1. **Probar en el navegador de punta a punta** — los 5 casos de la sección 1, el caso de la sección 2,
   y ahora los de `caso-prueba-reglas-duras.md` §11. Sin esto no hay que commitear — el endpoint
   nuevo ni se probó contra el stack real, solo compila y tiene tests unitarios con mocks.
2. Si algo falla, `git diff` para ver el alcance exacto — es un solo hilo de cambios relacionados
   (elegibilidad + hechos generadores), debería poder ir en 1-2 commits.
3. Si se quiere ir más allá de los tests unitarios: un test de integración real (Testcontainers,
   patrón `AbstractPersistenceIT`) para `POST /cases/eligibility` — hoy la cobertura es unit-level
   (`PolicyEligibilityValidatorTest` + los 4 casos nuevos de `CaseServiceImplTest`), nada ejercita
   el controller/la serialización HTTP real.
