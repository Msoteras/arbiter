# Brecha entre lo documentado y lo implementado

**Fecha del barrido:** 09/08/2026 · **Base:** `feature/front-details`.
**Reverificado contra el código:** 25/08/2026 (`feature/onboarding-asegurado`). Lo que se cerró
desde entonces está marcado en su sección; lo que sigue abierto se dejó como estaba.

Este documento cruza tres fuentes contra el código y registra las diferencias. No es una lista
de bugs: la mayoría son cosas que todavía no se implementaron y que sí están en el roadmap. Lo
que importa acá es **qué está declarado en un documento que un tercero va a leer** (el paper) y
qué modelo tiene que quedar fijado antes de escribir el código, para no implementarlo tres veces
distinto.

Fuentes:

| Fuente | Versión | Rol |
|--------|---------|-----|
| `Arbiter Historias de Usuario - UTN - 2026` | 1.0 · 26/05/2026 | 20 HU (H0001–H0020) agrupadas en 9 épicas |
| `Paper CONAIISI Arbiter` | 09/08/2026 | Descripción pública del sistema y del marco regulatorio |
| Código | `feature/front-details` | Lo que efectivamente corre |

Cuando las tres discrepan, **el paper manda**: es el documento que se publica y el único que cita
la normativa. Las HU se corrigen para alinearse a él, no al revés.

---

## 1 · Modelo de plazos legales — cerrar antes de implementar

Las tres fuentes arrancan el reloj en momentos distintos. Esto no se resuelve implementando: hay
que fijar un modelo único primero.

| Fuente | Plazo para expedirse | Desde cuándo cuenta |
|--------|----------------------|---------------------|
| **Paper** (§1.1) | 30 días | recepción de la **documentación completa** |
| **HU** (H0004) | 30 días Patrimoniales / 15 Personas | recepción de la **denuncia** |
| **Código** | 30 días | creación del expediente (= la denuncia) |

### Decisiones

**D1 — 30 días, sin bifurcación por tipo de seguro.** El alcance del prototipo son los ramos
*Celulares* y *Tecnología Portátil* (ver `arbiter_common.branch`), ambos patrimoniales. El plazo de
15 días de Seguros de Personas no aplica a ningún ramo configurado. **H0004 hay que corregirla**:
hoy pide una bifurcación que no corresponde al alcance. Si más adelante entra un ramo de Personas,
el plazo pasa a ser un parámetro del ramo en el motor de reglas, no una constante.

**D2 — El reloj arranca con la documentación completa, no con la denuncia.** Es lo que dice el
paper y lo correcto normativamente. Hoy el código hace
`responseDeadline = LocalDate.now() + 30` al crear el expediente
([`CaseServiceImpl:107`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/CaseServiceImpl.java)),
lo que adelanta el vencimiento y va a hacer que la alerta de H0017 avise en el momento equivocado
— peor que no avisar.

La buena noticia: **el disparador ya existe en la máquina de estados.** "Documentación completa" es
exactamente la salida de `AWAITING_DOCUMENTATION`, o el hecho de no haber entrado nunca a ese
estado. El plazo se fija cuando el expediente queda con la agenda documental satisfecha, no en el
`save` inicial.

**D3 — El plazo es dato de reglas, no constante de código.** Hoy es
`private static final int RESPONSE_TERM_DAYS = 30`, lo que choca con la decisión de arquitectura
#12 ("reglas de negocio dinámicas en BD") y con lo que el paper afirma en §3.1 ("modificar reglas
de negocio, umbrales y flujos sin desarrollo").

### Plazos del paper que no están en ninguna HU ni en el código

El paper (§1.1) cita cuatro plazos; el código modela uno (mal, por D2). Los tres restantes no
tienen HU:

- **72 h del asegurado para denunciar** desde el hecho. Validable hoy mismo comparando
  `eventDate` contra el momento de la denuncia. Es la regla más barata de todas y la única que
  puede rechazar una denuncia en el acto.
- **15 días para el pago** una vez aceptado el siniestro. Requiere un estado posterior a
  `APPROVED` — se conecta con el estado *Liquidado* que pide H0010 (ver §4).
- **Prescripción a los 18 meses** de la denuncia para siniestros no gestionados. Es justamente lo
  que justifica el estado *Prescripto* que H0006/H0010 piden y el enum no tiene. El paper ya trae
  la justificación normativa del estado que falta.

> **Nota de citas:** el paper apoya los cuatro plazos en `[2]` (Ley 17.418). Las 72 h y los 18
> meses no salen de esa ley — hay que citar la norma o resolución SSN correspondiente. Es el dato
> duro sobre el que se apoya toda la sección regulatoria.

### Qué implementar (H0017 depende de esto)

1. Fijar `responseDeadline` cuando la agenda documental queda satisfecha, no al crear el caso.
2. Mover el plazo a configuración de reglas por ramo.
3. Sumar las 72 h como validación de alta de denuncia.
4. Sumar los 18 meses y los 15 días de pago cuando entren los estados nuevos (§4).
5. Recién entonces, la alerta al 80% del plazo (H0017) tiene sobre qué calcular.

---

## 2 · Historias de usuario faltantes

Orden por criticidad, no por número.

### 🔴 Épica 9 completa — H0018, H0019, H0020

`reports-service` es scaffolding: `SecurityConfig`, config multi-tenant, una entidad `Metric` y su
repository. **Cero controllers, cero services.** El frontend tiene las dos pantallas maquetadas con
datos hardcodeados y el comentario `TODO EL DATO ES MOCK`
([`dashboard.component.ts`](../arbiter-frontend/src/app/features/admin/dashboard/dashboard.component.ts),
[`reportes.component.ts`](../arbiter-frontend/src/app/features/admin/reportes/reportes.component.ts)).

Peso extra: el paper lista *Reportes* como uno de los cinco módulos funcionales (§2.1) y define al
referente como quien "consulta los reportes operativos". Es un módulo declarado en la arquitectura
publicada.

### ✅ H0016 — Notificaciones automáticas al asegurado — **cerrado**

Ya hay emisor: `CaseNotificationService` (cases-service) manda mail y aviso en el panel en los
cuatro momentos que faltaban — denuncia recibida (`PENDING_CLASSIFICATION`), documentación
requerida (`AWAITING_DOCUMENTATION`) y resolución (`APPROVED` / `REJECTED`) — más
`ExpertNotificationService` para la derivación a perito. Solo esos estados: el resto es tráfico
interno y contarlo filtraría lo que la historia prohíbe (clasificación, score, motivos).

El paper lo afirma dos veces como capacidad existente (§2.2 y §3.2) y lo usa como el ataque directo
al 63,5% de asegurados que hoy tienen que llamar a la aseguradora — es el principal argumento de
valor para el asegurado en todo el trabajo.

### 🔴 H0017 — Alertas por vencimiento de plazos

`Case.responseDeadline` se escribe y nadie lo lee nunca. No hay `@Scheduled` que barra plazos (el
único job es `ClassificationRefreshScheduler`), ni umbral del 80%, ni alerta en el panel, ni
configuración del umbral en reglas. **Depende de cerrar §1 primero.**

### 🔴 H0006 + H0010 — Estados del ciclo de vida

Los 7 estados actuales (`PENDING_CLASSIFICATION`, `PENDING_ANALYST_REVIEW`,
`CLASSIFICATION_FAILED`, `AWAITING_DOCUMENTATION`, `PENDING_EXPERT_REPORT`, `APPROVED`,
`REJECTED`) se usan de verdad y son
coherentes con el flujo implementado. El problema es que las HU declaran los 12 estados oficiales
del NSIN001 y falta la mitad. Ver §4 — va junto con el refactor de transiciones.

### 🟠 H0008 — Clasificación automática del tipo de siniestro

No existe. El hecho generador lo **elige el asegurado** en el wizard y `CaseReferenceResolver` solo
lo valida contra el catálogo. El modelo produce una recomendación de resolución, que es H0014.

Decisión pendiente: implementarla, o reescribir H0008 para que describa lo que el modelo realmente
hace. El paper (§1.1) describe las categorías operativas reales de la aseguradora de referencia
—*siniestro exprés, documentación reducida, documentación amplia, urgente, derivación a servicio
técnico*— y afirma que el Fast Track automatiza esa clasificación. Si esa es la intención, H0008 es
la HU que la cubre y hay que alinearla a esos nombres.

### 🟠 H0004 — Validaciones de la denuncia — **parcialmente cerrado**

✅ Las tres validaciones del paper (§1.1) las hace hoy `PolicyEligibilityValidator`: vigencia a la
fecha del hecho, mora, carencia y exclusión de cobertura, todas en el alta y antes de que exista
expediente.

Sigue abierto: que la póliza **pertenezca a ese asegurado** (se resuelven póliza e insured por
separado, sin cruzarlos).

Faltan además: el mail de confirmación con número de expediente y la aclaración del Art. 56, y las
72 h de §1.

### 🟠 H0007 — Extracción de datos de documentos

`OllamaDocumentAnalyzer` transcribe el documento a **texto libre** con Qwen3-VL y lo inyecta en el
prompt. De los cuatro criterios de aceptación está uno. Faltan: datos **estructurados** (nro de
factura, marca, modelo, nro de serie), validación contra los datos de la póliza, y que el analista
los vea y los pueda corregir — `grep -rn "ocr\|extract" arbiter-frontend/src` no devuelve nada.

### 🟠 H0014 — Justificación de la decisión

`AnalystDecisionRequest.justification` sigue siendo un `String` sin `@NotBlank`, y
`recordAnalystDecision` no compara la decisión contra la recomendación del modelo: el criterio "si
decide diferente debe ingresar justificación escrita" no está.

✅ **Derivar** sí existe, aunque como flujo aparte y no como tercera opción de la decisión:
derivación a perito con su propio estado (`PENDING_EXPERT_REPORT`) y su notificación.

El paper afirma en §2.2 que la clasificación requiere "la aprobación o el rechazo explícito y
**justificado** de un analista". Es un `@NotBlank` condicional: el arreglo más chico de esta lista
con la mayor exposición.

### 🟡 H0013 — Fast Track solo del lado aprobación

`FastTrackValidator.Result` es `boolean fastTrack` + razones: es un gate de aprobación. No existe
el Fast Track de rechazo (póliza vencida, cobertura inaplicable), que tanto la HU como el paper
piden explícitamente ("agilizando tanto las aprobaciones como los rechazos", §2.2).

✅ Lo relacionado ya se cerró: las **exclusiones** de cobertura dejaron de ser texto para el prompt
y son reglas evaluables (`COVERAGE_EXCLUSION` servida por `rules-service`, evaluada por
`CoverageRuleEvaluator` antes del gate de Fast Track). Una exclusión objetiva ya no es criterio del
modelo.

### 🟡 Menores

- **H0005**: `CaseDocument` no tiene estado de validación → "el asegurado no puede modificar
  documentos ya validados" no es exigible.
- **H0002**: faltan los campos **Sector/área** (obligatorio) y **Fecha de ingreso** (opcional) en
  `CreateUserRequest`. Y "desactivar usuarios" no existe: solo `DELETE` duro —
  `UserStatus.INACTIVE` no lo produce ningún flujo.
- **H0012**: el recálculo al subir documentación está (`addDocumentsAndReclassify` resetea score y
  reclasifica), falta la **nota de ajuste manual** del analista. Además el score implementado es
  `[0.0, 1.0]` con cortes de banda configurables, no el 0–100 con bandas fijas 0-30/31-60/61-80/
  81-100 que declara la HU. **Corregir la HU**, el código está mejor.

### Completas

H0001 (incluido el bloqueo de 5 intentos / 15 min), H0003, H0009 (más de lo pedido), H0011
(filtros + texto libre + export CSV/XLSX en el cliente).

---

## 3 · Implementado sin HU que lo respalde

En la defensa esto pesa igual que una HU sin implementar. Casi todo se resuelve **agregando la HU**,
no tocando código.

1. **Asignación de expedientes a analistas** — `POST/DELETE /cases/{id}/assign`,
   `GET /users/analysts`, la lente "Míos"/"Todos" de la bandeja, reasignaciones en el historial.
   Una feature entera. El paper sí la menciona ("cuando el analista se lo asigna, la recomendación
   ya está disponible", §2.1); las HU no.
2. **Motor de reglas configurable** — exclusiones de cobertura, textos de reglas al prompt, agenda
   documental por ramo + hecho generador, CRUD de coberturas, configuración de scoring (factores,
   pesos, bandas) con historial de cambios. H0015 menciona "umbrales" al pasar; el resto de
   `rules-service` no está descrito en ninguna parte.
3. **Búsqueda web inversa de imágenes (Google Vision)** — ver §5. Es el punto con más riesgo.
4. **Multi-tenant por esquema**, y una regla de negocio real sin documentar: la denuncia se
   registra en el tenant de **la póliza**, no en el del login
   ([`CaseServiceImpl:74`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/CaseServiceImpl.java)),
   para el asegurado con pólizas en dos compañías.
5. **Recuperación de contraseña y activación por token** — `/forgot-password`, `/reset-password`,
   `/activate`, `/invite-tokens/{token}`. H0001 y H0002 no los mencionan.
6. **`CLASSIFICATION_FAILED` + reintento manual** (`POST /{id}/retry-classification`) — un estado
   del ciclo de vida y un endpoint sin HU, que además no está entre los estados oficiales de H0010.
7. **PEP y consentimiento de uso de imagen** en la denuncia — no están entre los campos de H0004.
8. **`embedding-service`** (sidecar Python con CLIP) — componente de arquitectura sin HU. Tampoco
   figura entre los cinco módulos que enumera el paper en §2.1.
9. Menores: export a XLSX (H0011 pide solo CSV), `GET /policies/{nro}`,
   `GET /coverages/detailed`.

---

## 4 · Refactor de estados y transiciones — hacerlo una sola vez

Dos cosas separadas que conviene resolver juntas, porque la segunda duplica el trabajo si se hace
después.

### 4.1 Faltan 6 estados

Actuales (6): `PENDING_CLASSIFICATION`, `PENDING_ANALYST_REVIEW`, `CLASSIFICATION_FAILED`,
`AWAITING_DOCUMENTATION`, `APPROVED`, `REJECTED`.

Declarados en H0006/H0010 (12): Siniestro Denunciado, Solicitud Docum. Adicional, Envío A Estudio
Analista, Envío Estudio Técnico, Derivación A Cristalería, Derivar A Médico Evaluador, Autorizado A
Analista, Liquidado, Rechazado, Desistido, Caso Caduco, Prescripto. Más **Rehabilitado**, que las HU
piden como paso obligatorio para reabrir un expediente terminal.

Faltan los estados de derivación (cristalería, médico evaluador, estudio técnico), los terminales
*Liquidado*, *Desistido*, *Caso Caduco*, *Prescripto*, y la reapertura: `VALID_TRANSITIONS` no tiene
ninguna salida desde `APPROVED`/`REJECTED`.

Hay que decidir además el mapeo entre los estados actuales y los oficiales: `PENDING_CLASSIFICATION`
y `PENDING_ANALYST_REVIEW` son detalle interno de Arbiter que el NSIN001 no tiene, y
`CLASSIFICATION_FAILED` menos. Esa asimetría ya está resuelta a medias con la columna
`case_status.insured_status`, que colapsa los estados internos en lo que el asegurado puede ver.
El mismo mecanismo puede sostener "estado interno vs. estado oficial".

### 4.2 Las transiciones están hardcodeadas

`VALID_TRANSITIONS` es un `static final Map` en
[`CaseStatusService:29`](../cases-service/src/main/java/ar/edu/utn/frba/arbiter/cases/services/CaseStatusService.java).
Y `cases-service` **no tiene ni la URL de rules-service** en su `application.yml` (solo
`CLASSIFICATION_SERVICE_URL`): no hay cliente, no hay adapter, no hay llamada.

Esto contradice tres documentos a la vez:

- **Paper §2.2**: *"Cada transición de estado del expediente se valida contra el flujo definido por
  la aseguradora en el módulo de Reglas de Negocio."*
- **Paper §3.1**: *"permite modificar reglas de negocio, umbrales y flujos sin desarrollo."*
- **CLAUDE.md, decisión #12**: reglas dinámicas en BD, no en `if`s ni en `Strategy`.
- **H0015**: el referente define los estados activos y las transiciones permitidas.

Es la diferencia entre "configurable sin desarrollo" y "configurable recompilando" — justo la
crítica que el paper le hace a la competencia en §4.

### 4.3 Por qué junto

Agregar 6 estados al `Map` hardcodeado y después migrar las transiciones a BD es hacer el trabajo
dos veces, la segunda con el doble de casos y con expedientes ya existentes en estados nuevos. El
orden que tiene sentido:

1. Modelar transiciones como datos (tabla en BD, administrada por `rules-service`), con los 6
   estados actuales como seed. A igual comportamiento, sin estados nuevos: el refactor se valida
   contra los tests que ya existen (`CaseStatusLifecycleTests`).
2. Cablear `cases-service → rules-service` (URL, `RestClient`, DTO en `common-lib`, timeout y manejo
   de error explícito). Es la pieza que hoy no existe en absoluto.
3. Recién entonces sumar los estados nuevos, que pasan a ser filas y no código.
4. Con las transiciones ya en datos, H0015 (estados activos, transiciones permitidas, plazos por
   estado, etapas opcionales) queda a un CRUD de distancia.

Nota: `classification-service` **ya resolvió** su lado — `RulesRestAdapter` es `@Primary` sobre
`MockRulesAdapter` desde el commit `0ed76ec`. El que quedó sin cablear es `cases-service`.

---

## 5 · Google Vision: consentimiento decorativo — ✅ cerrado

> **Cerrado (25/08/2026).** Los cuatro puntos del cierre están implementados: `imageConsent` viaja
> en `ClaimReport` (cases → classification), llega a `ImageFraudAnalysisService.analyze(...)`,
> gatea **solo** la escalada a Google Vision —el CLIP interno sigue corriendo, nunca sale del
> host— y queda registrado en `ImageForensicReport.imageConsent` con su traza para el analista
> ("no se buscó en internet — el asegurado no dio su consentimiento"). El campo es `Boolean` y no
> primitivo: `null` distingue los reportes anteriores al cambio de un rechazo real.
>
> Lo que sigue abierto es lo del paper: el párrafo sobre la cascada como **niveles de exposición
> del dato**. Lo de abajo queda como el porqué del diseño.

`insured.image_consent` se pide en el formulario de denuncia, viaja en `CaseRequest`, se persiste en
`CaseReferenceResolver` — y **nadie lo lee nunca**. `ClaimReport` (el contrato cases →
classification) no lo lleva, así que classification-service ni se entera, y
`ImageFraudAnalysisService` / `GoogleVisionClient` no lo consultan.

Mientras tanto, `GoogleVisionClient` hace `POST` de la imagen del siniestro en base64 a
`vision.googleapis.com`. Está bien acotado —opt-in (`GOOGLE_VISION_ENABLED:false` por defecto),
cascada interna primero, degradación silenciosa si falla— y el `application.yml` lo dice explícito:
*"Sends claim images to a third party — opt-in only, requires the insured's consent"*. Pero ese
"requires the insured's consent" hoy no está implementado.

Importa porque el paper reclama como contribución el aislamiento de datos por compañía (abstract),
el LLM en infraestructura propia, y el cumplimiento de la **Ley 25.326** (§3.2). Un evaluador que
lea eso y después vea la integración tiene una objeción legítima.

### Cierre

1. Sumar `imageConsent` a `ClaimReport` y llenarlo desde `CaseServiceImpl`.
2. Propagarlo hasta `ImageFraudAnalysisService.analyze(...)`.
3. Gatear **solo la escalada a Google Vision**. La comparación interna (CLIP + pgvector) nunca sale
   del host: el consentimiento de terceros no le aplica. Sin consentimiento → matches internos +
   `WebFinding.none()`.
4. Registrar en `ImageForensicReport` que la búsqueda se omitió por falta de consentimiento — hoy
   `webSearchesPerformed` no distingue "no hizo falta" de "no estaba permitido". Es la evidencia
   auditable.

Y sumar al paper un párrafo sobre la cascada: no es solo costo y latencia, son **niveles de
exposición del dato**. Interno siempre, externo solo con consentimiento explícito y desactivable
por configuración. Bien contado, deja de ser una vulnerabilidad y pasa a ser evidencia de que el
trade-off privacidad/detección se pensó.

---

## 6 · Orden sugerido

| # | Trabajo | Desbloquea |
|---|---------|------------|
| 1 | Cerrar el modelo de plazos (§1) | H0017, y evita implementarlo mal |
| 2 | `@NotBlank` condicional en la justificación (§2, H0014) | afirmación del paper §2.2 · es chico |
| 3 | Gatear Google Vision con el consentimiento (§5) | riesgo regulatorio |
| 4 | Transiciones a BD + cableado `cases → rules` (§4.2) | H0015, y los estados nuevos |
| 5 | Estados nuevos como datos (§4.1) | H0006, H0010 |
| 6 | Notificaciones al asegurado (§2, H0016) | el argumento de valor central del paper |
| 7 | Alertas de vencimiento (§2, H0017) | depende de 1 |
| 8 | `reports-service` (§2, épica 9) | H0018, H0019, H0020 · la UI ya está maquetada |

En paralelo, y más barato que cualquiera de estos: **actualizar el documento de HU** con los ítems
1, 2, 4 y 5 de §3, y corregir H0004 (bifurcación de plazos que no aplica) y H0012 (escala del
score).
