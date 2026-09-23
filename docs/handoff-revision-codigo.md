# Handoff: revisión de código pendiente

Revisión general hecha el 22/09/2026 sobre `fix/ui-pestanas-analisis`. La limpieza de documentación,
código muerto y comentarios se hizo el 23/09 en `chore/limpieza-codigo`. Este documento junta lo que
falta.

Este archivo se borra cuando se vacíe.

---

## 1. Temas en espera (necesitan charla, no código)

### 1.1 Liquidación mayor que lo reclamado
El cálculo del monto a pagar **no usa el monto reclamado**: parte de la suma asegurada de la
cobertura. Casos vistos:
- #32: reclamó $350.000 y se liquidó $960.000. El servicio técnico lo declaró irreparable y la
  fórmula pasó a pérdida total: suma asegurada ($1.200.000) menos franquicia del 20%.
- #37 (prueba del 23/09): robo, reclamó $900.000 y la propuesta da $1.170.000 = suma asegurada
  ($1.300.000) menos franquicia del 10%.

Opciones:
1. Dejarlo así: lo fija la póliza, no lo que pide el asegurado (el reclamado es opcional y suele
   ponerse a ojo). El analista ya puede bajar el monto a mano al aprobar.
2. Tope en lo reclamado: pagar lo menor entre el cálculo y lo pedido. Evita pagar de más, pero
   castiga a quien puso un número bajo.
3. Tope por valor de reposición: configurar la cobertura con `LESSER_OF_SUM_AND_REPLACEMENT`
   (ya existe) para que el tope sea el valor acreditado por documentación (p. ej. la factura). Hoy
   "Robo de celular" usa `SUM_INSURED`. Es la que más se apoya en documentación y no en lo declarado.

Código: `SettlementCalculator` y `SettlementService` (cases-service); enums `SettlementFormula` y
`SettlementBasis` (common-lib).

### 1.2 "17" contra "18" siniestros previos
No es un bug de cálculo: es el mismo dato contado de dos formas.
- La regla `MAX_EVENTS_YEAR` guarda `events12m`, que es el número de evento **contando este
  siniestro** (18). La pantalla lo muestra como "18 siniestros en los últimos 12 meses".
- El motivo de esa misma regla dice cuántos hubo **antes** (17).
- El score y el modelo usan sus propios conteos de "previos".

Falta decidir un solo criterio para todo lo que ve el analista. Código: `TemporalRuleEvaluator`
(classification-service) y `core/models/trazabilidad.ts` (front).

### 1.3 Chat en tiempo real en desarrollo
`arbiter-frontend/proxy.conf.json` tiene un cambio **sin commitear**: `changeOrigin: false` en
`/api/v1/ws`. Sin eso, el WebSocket del chat da 403 en `ng serve`. En producción nginx manda
`Host $host` y debería andar, pero conviene confirmarlo en Railway. Si se confirma, commitear el
cambio del proxy o fijar `setAllowedOriginPatterns` en `WebSocketConfig`.

### 1.4 PRs a `main` desde `hotfix/*`
`.github/workflows/main.yml` acepta `hotfix/*` y `guard-main.yml` no, así que un hotfix nunca pasa.
Hay que decidir si se aceptan hotfixes directos a `main` y dejar un solo workflow.

### 1.5 Qué cobertura responde por una rotura
En la póliza BBVA de celulares (Railway), "Robo de celular" solo excluye Hurto, así que también
"cubre" Rotura accidental y Caída; como está primera en el orden de la compañía, **toda** rotura
cae ahí (se vio en el #44: una caída común quedó con franquicia de robo, 10%, en vez de daño
accidental, 20%, y con los plazos de robo). La cobertura se elige solo por el hecho generador
declarado; el relato no interviene, y el analista no puede cambiarla después.

Argumento a favor de que robo cubra algunas roturas: si el celular se rompe en un intento de
arrebato, es razonable que responda robo. Opciones:
1. Un hecho generador aparte ("Rotura por intento de robo") que cubra solo "Robo de celular", y que
   "Rotura accidental" y "Caída" las excluya robo. Es configuración, sin código.
2. Que el analista pueda reasignar la cobertura mirando el relato (requiere desarrollo).

Antes de decidir: confirmar qué dice la póliza de la compañía sobre daños por intento de robo.
Código: `PolicyCoverageResolver` (cases-service).

---

## 2. Bugs encontrados durante la limpieza

- **Columna sin uso en Railway.** `document_analysis.described_claim_cause` existe en los dos
  tenants de Railway pero el código no la usa y el script base no la crea. Se puede borrar avisando
  al equipo. (La tabla `metric` que también aparece es de las métricas en desarrollo: no tocarla.)

---

## 3. Restos del flujo de prueba aislado (classification-service)

Se borraron los endpoints de prueba, pero quedaron piezas que solo existían para ellos y que ya
cambian lógica al sacarlas:
- Los guards `caseId == null` en `CaseOutcomeRepository` y `ClassificationResultsService`, y la rama
  `caseDocumentId == null` en `ImageEmbeddingService`.
- Los overloads `ClassificationOrchestrator.classify(ClaimReport)` y `classify(ClaimReport, List)`:
  producción no los usa, pero sostienen ~25 tests del orquestador.

---

## 4. Convenciones (CLAUDE.md) que no se cumplen

- **Identificadores en castellano.** Los comentarios ya están en inglés, pero siguen en castellano
  nombres de archivos, componentes y funciones del front (`bandeja`, `expediente-detail`,
  `nueva-denuncia`, `estadoTone`…) y nombres de tests del back. Renombrarlos es un refactor grande.
- **Lógica en controllers:** `ClaimCauseController` (repositorio, try/catch y filtros) e
  `InsuredProfileController`.
- **Respuestas armadas con `Map` en vez de DTO:** `CaseController` y `ClaimController`.
- **Servicios con prefijo de mecanismo:** los seis `Internal*` de rules-service.
- **Propiedades sin declarar:** `arbiter.provisioning.invite-delay-ms` y `max-invites-per-run`
  (auth-service) no están en ningún yml.
- **Autor del historial de reglas:** se guarda `changed_by = null` en los 8 servicios que escriben
  historial, y el autor se saca del texto del motivo (`RuleChangeHistoryService.actorOf`). Lo correcto
  es guardar el id del usuario al escribir.
- **Front:** botones hechos a mano que deberían ser del kit (chips y "Limpiar todo" de la bandeja,
  `.remove-cov` en reglas) y `max-width` en `ch`/`.measure` sobre párrafos dentro de cards (riesgo de
  texto cortado).

---

## 5. Otros hallazgos (no urgentes)

- **Historial de reglas con literales crudos.** Los cambios del objetivo de resolución se muestran como
  `RESOLUTION_TARGET` y `TARGETDAYS` en vez de un label en castellano.
- **Archivos sin referencias:** `docs/wireframes/*.html`, un PDF en `docs/siniestros/` y PNGs en
  `public/brand/`. Confirmar si se conservan.
- **Formulario "Identificate"** en `mis-expedientes`: quedó de antes de Auth0; revisar si sigue haciendo falta.
- **Rendimiento en local:** `GET /cases/{id}` ~11 s, `GET /settlement-authorities` ~21 s para 2 ramos
  y guardar una decisión ~10 s. Deployado anda mejor. Si vuelve a notarse, perfilar las N+1.
- **Reglas en tablet:** al elegir una sección del menú, el contenido carga abajo y la página no se
  desplaza hasta ahí.
- **Monto a pagar** en el modal de aprobar: campo numérico nativo que acepta la letra "e". Podría
  pasar a `app-input` con `prefix="$"`, como Atribuciones.
- **Inicio del analista:** "Resumen de Arbiter · Próximamente" es un placeholder a la vista.
