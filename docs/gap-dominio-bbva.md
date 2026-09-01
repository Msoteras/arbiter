# Baches de dominio vs. procedimiento real de BBVA

Comparación entre el código actual de Arbiter y `Siniestros_NSIN001` (procedimiento interno de
gestión de siniestros de BBVA Seguros, resumido en el doc de dominio recibido el 31/08/2026).
**No es una lista de bugs**: separa gaps reales de modelado, cosas ya cubiertas que conviene no
tocar, y decisiones de negocio pendientes que el propio doc fuente deja abiertas.

Cada entrada cita archivo:línea de donde se verificó el estado actual (31/08/2026). El código
avanza — antes de actuar sobre una entrada vieja, confirmar que la cita sigue vigente.

---

## 1. Control de vencimientos — el más importante según el doc fuente ✅ resuelto (31/08/2026)

El doc (§9) llama a esto "una función central del sistema, no un accesorio", porque el silencio de
la aseguradora implica aceptación legal (art. 56 Ley 17.418).

**Ya cubierto — no rehacer:** existe `DeadlineSweepScheduler`
(`cases-service/src/main/java/.../services/DeadlineSweepScheduler.java`), corre diario, detecta
casos CRITICAL/OVERDUE a 2 días del vencimiento y notifica al analista (H0017). El semáforo
(`DeadlinePriority`) se deriva on-read en `CaseResponse`. Esto ya resuelve el automatismo que el
doc marca como oportunidad en §12.1.

**Resuelto — el plazo ahora se interrumpe y se reanuda entero.** `CaseStatusService.transition()`
resetea `responseDeadline` a 30 días desde hoy (`RESPONSE_TERM_DAYS`, movida a esa clase) cada vez
que el expediente **sale** de `AWAITING_DOCUMENTATION`/`PENDING_EXPERT_REPORT` (`PAUSING_STATUSES`)
hacia un estado activo — coherente con el doc (§1, §8: el plazo "vuelve a correr entero", no lo que
quedaba). Mientras el expediente está pausado, `DeadlineSweepScheduler` y la lente "Por vencer"
(`CaseSpecifications.dueSoonBefore`) lo excluyen de la alerta — el semáforo no lee una fecha
congelada como urgencia real. La salida hacia `LAPSED` (caducidad, ver más abajo) queda excluida a
propósito del reset: el requerimiento ahí nunca se cumplió, no hay plazo que reanudar.

**Corrección 31/08/2026 — esto también quedó resuelto, como efecto colateral.** La nota anterior
decía que el plazo seguía arrancando desde la denuncia y no desde la documentación completa
([[project-plazos-legales-modelo]]). Verificado contra el flujo real:

- Cuando el motor clasifica `FALTA_DOCUMENTACION`, el poller mueve el expediente
  `PENDING_CLASSIFICATION → AWAITING_DOCUMENTATION` (`ClassificationServiceClient.statusFor`) — el
  origen no es un estado que pausa, así que el reset no aplica (correcto: recién está entrando a
  esperar).
- Cuando el asegurado sube lo que falta, `CaseServiceImpl` transiciona
  `AWAITING_DOCUMENTATION → PENDING_CLASSIFICATION` (línea ~265) — **ese es un `PAUSING_STATUSES` de
  salida**, así que `CaseStatusService.transition()` resetea `responseDeadline` a 30 días **desde
  ese momento**. Si la carga sigue incompleta, vuelve a `AWAITING_DOCUMENTATION` y el ciclo se
  repite; el reset que queda en pie es siempre el de la **última** subida, que es el mejor proxy que
  el sistema tiene de "documentación completa" (el margen de error es el tiempo de reclasificación
  asincrónica, minutos — no el mes completo que había antes).
- Un expediente que nunca pasa por `AWAITING_DOCUMENTATION` (documentación completa desde la
  denuncia) sigue con el `responseDeadline` fijado en la creación — y ahí sí es correcto, porque la
  denuncia y la documentación completa **son el mismo día**.

No hizo falta ningún cambio adicional de código: el mecanismo de interrupción/reanudación que se
armó para el punto 1 general ya cubre este caso porque usa exactamente el mismo gancho
(`CaseStatusService.transition`). Dato para no repetir la confusión: mientras el expediente está en
`AWAITING_DOCUMENTATION`, el valor viejo de `responseDeadline` sigue en la fila (estampado en la
creación) pero **nadie lo lee como urgencia real** — `isDeadlineInactive`/`DeadlineSweepScheduler`
lo excluyen — así que no importa que esté desactualizado hasta que se pisa con el valor bueno al
salir.

**No es un bug — es una divergencia deliberada a sostener:** el doc describe que el vencimiento sin
pronunciamiento implica aceptación automática del siniestro. Arbiter no automatiza eso — la
decisión de arquitectura #5 (human-in-the-loop obligatorio, ninguna resolución automática) es
anterior y más fuerte que el mecanismo legal descripto. Vale la pena tenerlo explícito para
defender la diferencia ante la cátedra, en la misma línea que el punto 5 de este documento.

**Resuelto — prescripción y caducidad ya existen, con un matiz de diseño.**

- **Prescripción (1 año desde el hecho, patrimoniales)**: nuevo gate determinístico en
  `ClassificationOrchestrator` (`isPrescribed`/`prescriptionResponse`, evaluado antes que cualquier
  otra regla). Si `reportedAt` > `eventDate + 1 año`, la clasificación sale directo en
  `LLM_NO_RECOMIENDA_APROBAR` (sugiere rechazar) sin llamar al LLM — no
  `LLM_SOLICITA_REVISION_MANUAL`: a diferencia de una exclusión de cobertura, acá no queda nada
  interpretativo por revisar una vez leídas las dos fechas. Constante Java fija
  (`PRESCRIPTION_YEARS`), no un `insurer_rule` — es ley, no una condición del contrato que el
  referente pueda apagar (mismo criterio que `RESPONSE_TERM_DAYS`). Alcance acotado a 1 año
  general (los ramos configurados hoy son patrimoniales); los 3 años de Vida o de reclamos de
  terceros no aplican y no se modelaron.
- **Caducidad por inacción (18 meses desde la denuncia sin movimiento)**: nuevo estado terminal
  `CaseStatus.LAPSED` + `LapseSweepScheduler` (cron diario), que cierra a `LAPSED` los expedientes
  en `AWAITING_DOCUMENTATION` con `reportedAt` de hace 18+ meses. Notifica al asegurado
  (`CaseNotificationService`).

**Matiz frente al punto 2:** ninguna de las dos crea un estado `PRESCRIPTO` separado. La
prescripción hoy es una **señal de clasificación** que empuja al analista a rechazar por el canal
normal (`REJECTED`) — no resuelve el expediente por sí sola (decisión de arquitectura #5). Solo la
caducidad por inacción tiene estado terminal propio (`LAPSED`), porque ahí no hay ninguna decisión
que tomar: nadie contestó. Si el equipo quiere que el rechazo por prescripción deje una traza más
específica que un `REJECTED` genérico, ahí sí haría falta un estado dedicado — no se hizo en este
trabajo por no inventar semántica que el doc no pide explícitamente para ese caso.

---

## 2. Máquina de estados — 7 valores vs. 14+ del proceso real

`CaseStatus` (buscar en `common-lib/.../enums/CaseStatus.java`): `PENDING_CLASSIFICATION`,
`PENDING_ANALYST_REVIEW`, `CLASSIFICATION_FAILED`, `AWAITING_DOCUMENTATION`,
`PENDING_EXPERT_REPORT`, `APPROVED`, `REJECTED`. Transiciones en un `Map` hardcodeado
(`CaseStatusService.VALID_TRANSITIONS`), no en BD — gap ya conocido y con orden de migración
decidido ([[project-classification-no-llama-rules]]).

**Actualización 31/08/2026:** se sumó un séptimo/octavo estado, `LAPSED` (caducidad por inacción,
ver punto 1) — el enum pasó de 7 a 8 valores, con `AWAITING_DOCUMENTATION → LAPSED` como única
transición saliente nueva. El resto de este punto sigue abierto:

- ~~**`REHABILITADO`** — reabrir un expediente terminal.~~ ✅ **Resuelto 31/08/2026 — ver punto
  11.** Los tres terminales ya tienen salida a `PENDING_ANALYST_REVIEW`. No se creó un estado
  `REHABILITADO` separado: la reapertura es un *movimiento*, no un lugar donde el expediente se
  queda.
- **`PRESCRIPTO`** como estado dedicado — no se creó (ver el matiz del punto 1: hoy la prescripción
  resuelve por el `REJECTED` genérico). **`DESISTIDO`**, **`BAJA POR ERROR`** — siguen sin existir;
  sin ellos, la regla invariante del doc ("los terminales no admiten eventos sin pasar por
  REHABILITADO") sigue sin dónde apoyarse del todo.
- **`EN MEDIACION/JUICIO`** — no está.
- **`AUTORIZADO A LIQUIDAR`** / **`LIQUIDADO`** — el doc pide modelarlos como "estados terminales de
  frontera" que Arbiter conoce aunque no ejecute (§2), ni siquiera como placeholder existen hoy.
- **`DENUNCIA DE HECHO (RC)`** como estado activo sin reserva — ligado al punto 3.

---

## 3. Reservas (SPL) — no modelado

Cero entidad, columna o servicio relacionado (grep de "reserva"/"reserve"/"SPL"/"provision" en
`.java` de todo el repo solo trae falsos positivos: `InsuredProvisioning*`, comentarios con
"preserve"). El doc lo trata como control transversal, no como monto de pago: se abre con la
denuncia, se ajusta con cada valuación, se cierra en cada estado terminal (§8, §10) — es una de
sus "aserciones de test".

Esto linda con liquidación, que el doc de dominio confirma fuera del alcance de Arbiter (§2). Pero
como **control** (abierta/cerrada, no el monto) es barato de modelar y cierra una de las
invariantes de máquina de estados más citables del doc. Decisión de equipo pendiente: ¿vale la
pena como flag booleano por expediente, o se deja fuera de alcance junto con liquidación?

---

## 4. Tipologías de siniestro — dos ejes que hoy están mezclados en uno

El enum `Classification` (`common-lib/.../enums/Classification.java`: `FAST_TRACK`,
`FALTA_DOCUMENTACION`, `LLM_RECOMIENDA_APROBAR`, `LLM_NO_RECOMIENDA_APROBAR`,
`LLM_SOLICITA_REVISION_MANUAL`) responde **"¿lo apruebo?"** — es la recomendación del LLM al
analista. La tipología del doc (§7: Exprés, Doc. reducida, Doc. amplia, Servicio técnico,
Cristalería, Urgente/estudio liquidador) responde **"¿qué trámite sigue?"** — es routing
procedimental, anterior a cualquier decisión de aprobar/rechazar.

**Ya cubierto — no rehacer:** la derivación a "estudio liquidador" del doc (perito externo que
investiga causa y cuantifica daño, asignado por zona/especialidad — glosario §3) está bien resuelta
por `ExpertAssessment` + `ExpertFirm`
(`cases-service/.../models/entities/ExpertAssessment.java`,
`cases-service/.../services/ExpertFirmService.java`): catálogo de peritos por zona/ramo que
administra el referente, derivación con motivo, notificación, veredicto que vuelve al analista sin
resolver nada por su cuenta (coherente con la decisión de arquitectura #5). `FAST_TRACK` cubre
razonablemente el caso Exprés.

**Gap real:** no hay ningún lugar para **"derivación a servicio técnico"** ni **"derivación a
cristalería"** (ramas 22/23/24 del doc) — son conceptualmente distintas del peritaje: no investigan
causa, gestionan reparación/reposición con un proveedor. Grep de "cristalería"/"servicio técnico" en
código de producción: 0 resultados (solo aparecen en `docs/siniestros/` y fixtures de prueba).

**Punto abierto, no resuelto acá:** el campo `Case.destination` (VARCHAR libre, sin valores
definidos) parecía el hueco natural para estos routing outcomes, pero `docs/der-gaps.md` ya lo tiene
registrado como candidato a **dropear** — la hipótesis de trabajo del equipo (26/8/2026) es que la
funcionalidad que iba a cubrir ya la resolvieron `FraudRecordService`/`fraud_determined` y
`ExpertAssessment` por otro lado. Reutilizarlo para servicio técnico/cristalería contradice esa
decisión ya anotada. Dos caminos, a decidir con el equipo:
  - (a) generalizar el patrón `ExpertFirm`/`ExpertAssessment` con un tipo de proveedor
    (`ESTUDIO_LIQUIDADOR` / `SERVICIO_TECNICO` / `CRISTALERIA`), reusando catálogo + derivación +
    notificación;
  - (b) revivir `destination` con esos tres valores como enum cerrado.

---

## 5. AgendaDocumental — CORRECCIÓN: ya está cableada, no es un bache

Verificación inicial equivocada (basada en un comentario desactualizado). El flujo real:

- `RulesRestAdapter.overlayDocumentRequirements` (`classification-service/.../adapters/RulesRestAdapter.java:305`)
  llama a `GET /api/v1/rules/document-requirements/internal`.
- Ese endpoint (`DocumentRequirementController.internalByCoverage`,
  `rules-service/.../controllers/DocumentRequirementController.java:39`) resuelve vía
  `InternalDocumentRequirementService` → `DocumentRequirementService.get()` →
  `DocumentRequirementRepository` — JPA real contra la tabla `document_requirement`.
- `ClassificationOrchestrator.checkRequiredDocuments`
  (`classification-service/.../services/ClassificationOrchestrator.java:459`) usa esa lista como
  gate de documentación faltante.
- El referente administra la agenda por ramo + hecho generador vía
  `PUT /api/v1/rules/document-requirements` (mismo controller).

`MockRulesAdapter.requiredDocumentTypes` sigue existiendo solo como **baseline** cuando el
referente no configuró nada para esa cobertura+causa (patrón idéntico al de Fast Track y
exclusiones — `RulesRestAdapter` es `@Primary`).

**Acción menor ✅ hecha (31/08/2026):** el javadoc de la entidad `DocumentRequirement`
(`rules-service/.../models/entities/DocumentRequirement.java`) decía *"this table doesn't replace
that wiring yet"* — quedó desactualizado desde que se cableó y es lo que generó esta confusión. Ya
describe el flujo real (referente → `PUT` → `RulesRestAdapter` → `checkRequiredDocuments`) y deja
al mock explícitamente como baseline.

---

## 6. Agotamiento de cobertura por monto ✅ resuelto (31/08/2026)

El doc (§6.4) pide verificar que "las coberturas contratadas... no estén agotadas por siniestros
previos", y esto es sobre **monto**, no solo cantidad. Ya existían `MAX_EVENTS_YEAR` (D10, cuenta
cantidad de eventos en 12 meses) y `Coverage.claimExhaustsCoverage` (booleano: "un siniestro
liquidado agota todo"), pero faltaba el cálculo de **suma asegurada consumida vs. remanente**.

**Resuelto:** `CoverageScopeEvaluator.evaluateSumInsuredLimit`
(`classification-service/.../services/CoverageScopeEvaluator.java`), hermano del método que ya
evaluaba `claim_exhausts_coverage`. Suma los siniestros **liquidados** de la misma póliza
(`amountSettled`, con fallback a `amountClaimed`) más el reclamo actual, y bloquea Fast Track si
supera `policy.insuredAmount()` — el dato ya llega desde la BD Aseguradora vía `InsuredPolicy`, así
que no hizo falta ninguna `insurer_rule`/columna nueva ni tocar rules-service: no es un parámetro
que el referente configure, es un hecho de la póliza (mismo criterio que ya usaba el método
hermano). Sin período de póliza modelado (misma limitación ya admitida en `POLICY_STANDING`): es un
acumulado de por vida contra lo que devuelve el historial, no un tope que resetea por renovación.

---

## 7. Fraude — ya cubierto, mejor que el proceso real de BBVA

El doc mismo admite (§4) que BBVA no tiene un criterio definido de detección de fraude, solo
menciona la derivación a estudio liquidador "si se hallan indicadores" sin decir cuáles (§2.6,
§12.4). Arbiter ya tiene más que eso: `FraudRecordService` (antecedentes, escribe
`Case.fraudDetermined`), `ImageEmbeddingService` + pgvector (duplicados entre denuncias),
`ImageWebMatchEvaluator` (búsqueda de la imagen en la web). No es un bache, es un argumento de
valor a favor de Arbiter — vale la pena citarlo así en el paper, en paralelo al punto 8.

---

## 8. Cosas deliberadamente distintas del proceso real — no tocar, defender explícitamente

- **Roles**: 3 (`ASEGURADO`/`ANALISTA_SINIESTROS`/`REFERENTE_ASEGURADORA`) vs. los 8 roles
  operativos de BBVA (asesor telefónico, mesa de entradas, backoffice, gestión inicial, gestión
  complementaria, liquidador, analista de personas, jefe de siniestros). El doc fuente dice
  textualmente en §4 que esta es "una diferencia deliberada de Arbiter y conviene sostenerla
  explícitamente ante la cátedra" — no es un gap a cerrar.
- **Segmento Patrimoniales/Personas**: no existe como campo; los plazos son por-coverage, no
  por-segmento. El scope actual (Celulares + Tecnología Portátil) es 100% Patrimoniales, así que el
  plazo de 15 días de Personas no aplica hoy — consistente con
  [[project-plazos-legales-modelo]]. Si el proyecto suma un ramo de Personas, ahí sí hace falta el
  campo y la bifurcación de plazo.
- **Nómina de asegurados en pólizas colectivas** (control #5 de gestión inicial, solo Personas): no
  existe, mismo motivo — no aplica sin ramo Personas.
- **CAP / habilitación de pago / tesorería**: fuera de alcance por diseño propio del doc de dominio
  (§2, §12 no lo incluye) — correctamente no implementado.

---

## 9. Decisiones de negocio que el propio doc deja abiertas — no son bugs de código

- **Discrepancia de criterios para "documentación reducida"** entre canal telefónico (§5.1.1.2: 6
  meses de antigüedad + 1 siniestro en 3 años) y canal mail/web/físico (§5.1.2.2: renovación anual +
  1 siniestro en 2 años) — el doc mismo dice que no hay justificación para la diferencia y que hay
  que unificar antes de implementar, o confirmar con la aseguradora cuál rige. Afecta directamente
  a `MAX_EVENTS_YEAR`/`FAST_TRACK` si se decide modelar esta tipología intermedia.
- **Umbrales de monto (Anexo II)** no están disponibles en ninguna fuente. Cualquier cifra que
  aparezca en el paper o en seeds sin esa fuente es inventada — mantenerlos como parámetros
  configurables (ya es la línea del proyecto, decisión de arquitectura #12), nunca constantes.

---

## 10. Consistencia interna del estado `LAPSED` ✅ resuelto (31/08/2026)

Cross-check posterior a la implementación de la caducidad (punto 1). El estado se agregó bien en el
enum, la base (`case_status` id 8, `is_final = TRUE`), la máquina de estados, el sweep, el semáforo
de vencimiento y los textos del frontend (`estado.ts`), pero **quedaron sin actualizar los lugares
que enumeran los estados terminales a mano**. Ninguno rompía; todos mentían en un tablero:

- `CaseServiceImpl.FINAL_STATUS_NAMES` (APPROVED + REJECTED) es lo que `analystWorkload()` le pasa a
  `countActiveByAnalyst`: un expediente caducado le contaba como carga activa al analista **para
  siempre**, porque nada lo saca de `LAPSED`. Corregido sumando el estado.
- `analista-inicio.component.ts` calculaba `resueltos = APPROVED + REJECTED` y `enTramite = total -
  resueltos`, así que los caducados aparecían como "En trámite". Mismo problema en
  `referente-inicio.component.ts` (`activos = total - resueltos`), que además necesitaba una cuenta
  más. Los dos ahora usan `ESTADOS_FINALES`, exportado desde `estado.ts` junto a `isEstadoFinal` —
  enumerar los terminales a mano en cada pantalla es exactamente lo que produjo el desfasaje.
- `asegurado-inicio.component.ts` pintaba de **verde** cualquier terminal que no fuera `REJECTED`,
  así que al asegurado un siniestro caducado le llegaba con el tono de uno aprobado. Ahora el verde
  es solo `APPROVED`.
- El filtro de estados de la bandeja (`bandeja.component.ts`) se había quedado **dos veces** atrás
  del enum: le faltaban `PENDING_EXPERT_REPORT` (desde `2afd6c3`, la derivación a peritaje) y
  `LAPSED`. Un estado que existe en la bandeja pero no en su filtro es un expediente que el analista
  no puede aislar. Ahora lista los 8.

Dos correcciones menores de la misma pasada: `CaseServiceImpl` fijaba el `responseDeadline` de la
creación con `LocalDate.now()` en vez del `Clock` inyectado (el resto del cálculo de plazos ya usa
`LocalDate.now(clock)`), y los javadoc de `CaseRepository.findUnansweredDueBy` /
`countActiveByAnalyst` seguían diciendo "APPROVED, REJECTED" cuando los llamadores ya pasan listas
más largas.

**Deuda de tests ✅ cerrada (01/09/2026):** `LapseSweepScheduler`
(`LapseSweepSchedulerTest`, mismo esqueleto que `DeadlineSweepSchedulerTest`), el gate de
prescripción de `ClassificationOrchestrator`
(`ClassificationOrchestratorPrescriptionTest` — incluye el borde de "1 año y un día" y el caso sin
`reportedAt` donde el gate no participa) y el endpoint de reapertura, que no tenía test propio en
ninguna capa (`CaseServiceImplTest` para la orquestación del service — actor, prefijo del motivo,
404, propagación del 409 — y `CaseControllerTest` para el contrato HTTP: 200, 400 por `reason`
vacío, 404, 409). La interrupción/reanudación del plazo ya tenía cobertura
(`CaseStatusServiceTest.transition_toLapsed_doesNotResumeTheDeadline` y el de peritaje).

---

## 11. Reapertura de expedientes cerrados ("rehabilitación") ✅ resuelto (31/08/2026)

### Qué es, para el que no leyó el doc fuente

En el procedimiento de BBVA, **`REHABILITADO` es el único camino de vuelta desde un estado
terminal**. La regla estructural que el doc enuncia es: *un expediente cerrado no admite ningún
evento nuevo sin pasar antes por una rehabilitación*. O sea: cerrar no es borrar, y reabrir no es
"deshacer" — es un acto explícito, con responsable y motivo, que vuelve a poner el expediente en
circulación. Es lo que le da sentido a que los estados terminales sean terminales: **son terminales
porque hay exactamente una puerta de salida y está señalizada**, no porque el expediente
desaparezca.

Los tres casos reales que lo justifican, y que hasta hoy Arbiter no podía atender:

1. **El analista se equivocó.** Aprobó el que iba rechazado, o al revés. Sin reapertura, el error
   queda congelado: `APPROVED` y `REJECTED` no tenían ninguna transición saliente.
2. **El asegurado aparece tarde con la documentación.** El expediente ya caducó (`LAPSED`, ver
   punto 1), llega el papel que faltaba y no hay nada que hacer con él. Este caso lo creamos
   nosotros mismos al implementar la caducidad — la puerta de salida faltaba desde el mismo día en
   que se agregó el estado.
3. **Aparece información nueva** sobre un siniestro ya resuelto (un peritaje tardío, un
   antecedente de fraude que se descubre después).

### Cómo quedó implementado

**Sin estado nuevo.** La reapertura devuelve el expediente a `PENDING_ANALYST_REVIEW`, que es
literalmente lo que significa: hay una persona que tiene que volver a decidir. Un estado
`REHABILITADO` propio sería un lugar donde el expediente no hace nada — nadie se queda
"rehabilitado", te rehabilitan *hacia* algún lado. Y la traza, que es lo que el doc realmente pide,
ya la da `case_status_history`: queda la fila `APPROVED → PENDING_ANALYST_REVIEW` con actor, motivo
y timestamp. Esto además evita la checklist de ~10 puntos de contacto que cuesta cada valor nuevo
del enum (ver punto 10).

- **Transiciones**: `APPROVED`, `REJECTED` y `LAPSED` → `PENDING_ANALYST_REVIEW` en
  `CaseStatusService.VALID_TRANSITIONS`. Es la única salida de los tres.
- **Endpoint**: `POST /api/v1/cases/{caseId}/reopen`, con `reason` obligatorio (máx. 200 —
  `case_status_history.reason` es `VARCHAR(255)` y se guarda con prefijo). Desde un estado no
  terminal responde **409**: no hay nada que reabrir en un expediente que sigue abierto, y eso lo
  corta la máquina de estados sola, sin un `if` aparte.
- **Permisos**: `ANALISTA_SINIESTROS` **y** `REFERENTE_ASEGURADORA`, el mismo criterio que asignar
  (`canGestionar` en el frontend). Reabrir no resuelve nada: solo vuelve a poner el expediente
  frente a una persona, así que no choca con la decisión de arquitectura #5 (que reserva la
  *decisión* al analista).
- **Plazo del art. 56**: **arranca de cero**. Reabrir para corregir un error no puede entregarle al
  analista un expediente ya vencido. Salió de generalizar el mecanismo que ya existía: el reset
  ahora se expresa contra `CaseStatusService.isDeadlineRunning` en vez de contra
  `PAUSING_STATUSES`, y así los dos casos (se cumplió el requerimiento / se reabrió un cerrado)
  caen bajo la misma regla — *venías de un estado con el reloj parado y pasás a uno con el reloj
  corriendo*. De paso ese predicado quedó como definición única del semáforo de vencimiento, que
  estaba duplicada en tres listas (la causa raíz del punto 10).
- **Nada se resetea.** La decisión anterior ocurrió y su registro en classification-service es
  inmutable (Disposición SSN 2/2023); el score de riesgo y el antecedente de fraude son hechos del
  siniestro, no del veredicto. Reabrir no deja el expediente como si nunca se hubiera resuelto.
- **Frontend**: botón "Reabrir expediente" en la card de decisión del detalle, con modal de motivo
  calcado del de justificación. Se sumó además la rama `lapsed` a `decisionState()` — un expediente
  caducado caía en el `@default` y mostraba *"Sin clasificación disponible todavía"*, que es falso:
  no es que falte la clasificación, es que nadie decidió nunca.

### Un bug que la reapertura destapó

`estadoSimplificadoEfectivo` (el stepper del asegurado) es **monótono**: toma el máximo avance
histórico para que el progreso no vuelva al día 1 cuando el estado técnico retrocede — que es lo
que pasa cuando el asegurado sube la documentación faltante. Pero con una reapertura ese máximo
dejaba el stepper clavado en **"Terminado"** sobre un expediente que había vuelto a revisión. Se le
puso techo: `TERMINADO` solo si el expediente está cerrado **ahora**. La monotonía sigue protegiendo
`DENUNCIADO → EN_TRAMITE`, que es para lo que existía.

### Lo que deliberadamente NO se hizo

- **No se modelaron `DESISTIDO` ni `BAJA POR ERROR`**, que el punto 2 sigue listando. Con la puerta
  de salida abierta, hoy los dos se resuelven reabriendo y volviendo a resolver; son estados que
  agregan *precisión* al motivo del cierre, no capacidad nueva.

### Aviso al asegurado

Sí se le notifica (mail + panel), por decisión del equipo: el asegurado ya recibió el mail de la
resolución anterior, así que un expediente que vuelve a moverse sin explicación es peor que el
ruido de un aviso más.

El punto técnico es que **una reapertura no se puede distinguir por el estado destino**: aterriza en
`PENDING_ANALYST_REVIEW`, el mismo al que llega cualquier clasificación normal. `MESSAGES` de
`CaseNotificationService` está indexado por destino, así que sumar una entrada ahí le habría mandado
"reabrimos tu siniestro" a todo el mundo cada vez que el modelo termina de clasificar. La notificación
pertenece al **movimiento**, no al lugar donde cae. Cómo quedó:

- `CaseNotificationService.notifyReopened` con `type = "REOPENED"` propio — un `type` que no es un
  `CaseStatus`, para que el panel del asegurado pueda titularlo distinto ("Reabrimos tu siniestro")
  en vez de decir "en revisión" sobre un expediente que en realidad se reabrió.
- Se dispara desde `CaseStatusService.transition`, no desde `reopenCase`: la condición es *el
  origen era terminal y el destino es la revisión*, y resolverla ahí adentro significa que cualquier
  camino futuro que reabra un expediente notifica solo, sin que nadie se tenga que acordar. Además
  corre dentro de la misma transacción, que es lo que le permite leer al asegurado de la entidad.
- **El mail no dice por qué.** El motivo que escribe el analista es interno — puede nombrar una
  sospecha, un error o una pista de fraude ([[project-asegurado-vs-analista-visibility]]). Se le
  cuenta el hecho y se lo invita a preguntar. Hay un test que lo fija.
- El hilo del portal nombra el movimiento como "Reabrimos tu siniestro" en vez del genérico "un
  analista está revisando tu caso", para que coincida con lo que dice el mail.
- De paso, `NOTIFICATION_TITLES` del panel tampoco tenía `LAPSED`: la notificación de caducidad
  —que ya existía— caía en el título genérico "Novedades de tu siniestro".

---

## 12. Una póliza tiene VARIAS coberturas ✅ resuelto (01/09/2026)

No es un gap contra el doc de BBVA sino un **error de modelado propio**, detectado por Aylén al
leer el punto 3 de este documento. No era una decisión pendiente: la mayoría de las pólizas de
cualquier ramo tienen más de una cobertura, y nuestra propia BD Aseguradora ya lo modelaba bien.

### Qué estaba mal

El origen es explícito (`db/datos-aseguradoras.sql`): `compania 1──* poliza 1──* cobertura`, con el
comentario *"poliza (contrato; NO tiene suma asegurada: eso vive en cobertura)"* y una columna
`orden` que va `cobertura1..cobertura20`, que es la forma real de la API de BBVA. Arbiter, en
cambio, tenía `policy.coverage_id NOT NULL` y `policy.sum_insured`: **una** cobertura por póliza, y
la suma asegurada colgada del contrato en vez del riesgo.

El puente entre ambos era `PolicySynchronizer.resolveCoverage`, que hacía literalmente
`remote.coverages().get(0)` y descartaba el resto, con un javadoc que hablaba de *"the policy's
primary coverage"* — un concepto que no existe: hay varias, y cuál aplica lo decide el hecho
generador. Lo mismo en los dos `InsurerDatabaseAdapter`, que leían bien las N coberturas y después
colapsaban a `coverages.get(0)` para llenar `insuredAmount` y `deductible`.

**7 de las 12 pólizas del seed ya tenían 2 coberturas**, así que no era un caso de borde futuro.
Las consecuencias, con la póliza 1 (Robo $1.300.000 + Hurto $650.000, de la que Arbiter guardaba
solo Robo):

1. **El asegurado no podía denunciar un hurto que su póliza cubre.** El wizard filtraba los hechos
   generadores con `excludedClaimCauseIds(policy.getCoverage().getId())`, así que Hurto
   desaparecía del selector.
2. **Se clasificaba contra la cobertura equivocada**: franquicia, carencia, tope de eventos y plazo
   de denuncia salían todos del riesgo que no correspondía.
3. **Los montos también.** `amount_ratio` (el factor de scoring más pesado), el gate de monto del
   Fast Track y el agotamiento por suma asegurada del punto 6 se medían contra la suma asegurada de
   la primera cobertura — el doble de la que correspondía, en esa póliza.

### Cómo quedó

- **Tabla nueva `policy_coverage`** (`policy_id`, `coverage_id`, `display_order`, `sum_insured`,
  `deductible_pct`), y `policy` perdió `coverage_id` y `sum_insured`. Separada del catálogo
  `coverage` a propósito, que es lo que se acordó con el equipo: en `coverage` vive la
  **definición** del riesgo, que administra el referente y vale para toda la aseguradora (carencia,
  tope de eventos, plazo, grupo familiar); en `policy_coverage` vive lo **contratado** en esa
  póliza, que lo fija la compañía. Mezclarlas obligaba a una fila de catálogo por póliza.
  Migración no destructiva en `db/migrations/2026-09-01-policy-coverage.sql`. El backfill lee las
  coberturas **desde la BD Aseguradora**, no desde la columna vieja: copiar `policy.coverage_id`
  dejaría a cada póliza ya sincronizada con una sola cobertura —la equivocada— para siempre, porque
  `PolicySynchronizer` solo importa pólizas que Arbiter todavía no tiene
  (`CaseReferenceResolver.resolvePolicy` hace `findByExternalPolicyNumber().orElseGet(import)`). Los
  dos esquemas viven en la misma instancia Postgres (`arbiter_X` ↔ `aseguradora_X`, ver
  `InsurerDbSchema`), así que el backfill puede leer el origen y traer todas. Queda una red de
  seguridad que copia la columna vieja solo para las pólizas que el origen no tenga, porque una
  póliza sin ninguna fila en `policy_coverage` hace fallar el alta con 422.
- **`PolicySynchronizer` importa todas.** Una cobertura que el referente no configuró se saltea con
  warning en vez de voltear la importación entera; solo falla si no resuelve ninguna, y ahí nombra
  las coberturas y no la póliza (la póliza está, lo que falta es configuración).
- **`PolicyCoverageResolver`** (cases-service) decide cuál responde por el hecho denunciado, y el
  expediente se crea con ésa. El wizard ahora ofrece un hecho si **al menos una** cobertura
  responde por él — la intersección de las listas negras, no la lista de una sola.
- **`InsuredPolicy.forCoverage(...)`** (classification) estrecha `insuredAmount`/`deductible` a la
  cobertura que corresponde. Se aplica **una sola vez**, donde el orquestador trae la póliza, así
  que los nueve consumidores de esos dos campos (Fast Track, `amount_ratio`, el agotamiento, el
  prompt, el `policy_snapshot` auditado) quedan bien de una. Para eso `ClaimReport` ahora lleva
  `coverageName`: el id es de Arbiter y la BD Aseguradora solo conoce las coberturas por nombre.

### Las exclusiones de cobertura del seed estaban muertas ✅ arreglado (01/09/2026)

Al cablear el resolver apareció esto: la funcionalidad de exclusiones **está implementada entera**
—el referente las edita en "Hechos generadores NO cubiertos" de la solapa Coberturas, `PUT
/api/v1/rules/coverage-exclusions` las guarda, `/internal/evaluable` las expone,
`CoverageRuleEvaluator` las evalúa matcheando por `claim_cause` id y deja fila en `rule_result`, y
el wizard filtra el selector con ellas— pero **las dos filas del seed estaban cargadas con
`rule_type = 'COVERAGE_INCLUSION'`**, un tipo de regla que no existe en el código: `RuleType` no
tiene esa constante y los cuatro lectores filtran por `COVERAGE_EXCLUSION`.

O sea que no faltaba código: faltaba que el seed usara el tipo de regla que el sistema sabe leer.
Mientras tanto esas filas eran invisibles para el motor, toda cobertura cubría todo y el caso 6 del
handoff ("Hurto no cubierto") no se reproducía.

Se reescribieron como lista negra, que es lo que el sistema evalúa, sin cambiar el comportamiento
buscado. Los ramos de hoy tienen 4 y 3 hechos generadores, así que enumerar lo NO cubierto es igual
de explícito que enumerar lo cubierto:

- Ramo 1 · Celulares (`claim_cause` 1 Rotura accidental, 2 Robo en vía pública, 3 Hurto, 4 Caída):
  la cobertura de Robo excluye 1, 3 y 4; la de Hurto excluye 1, 2 y 4.
- Ramo 2 · Tecnología Portátil (6 Daño accidental, 7 Robo en vía pública, 8 Hurto): Daño accidental
  excluye 7 y 8. Esta cobertura directamente **no tenía regla**, así que cubría todo el ramo.

Con las listas cargadas, cada hecho generador de un ramo lo cubre exactamente una cobertura, que es
lo que `PolicyCoverageResolver` necesita para elegir bien. Migración para las bases que ya existen
en `db/migrations/2026-09-01-coverage-exclusion-viva.sql` (convierte las filas viejas, desactiva
cualquier `COVERAGE_INCLUSION` que quede e inserta la de Daño accidental).

**Una cobertura sin exclusiones ahora avisa, y no bloquea.** El comentario del seed viejo
argumentaba que la lista blanca era mejor porque una cobertura sin regla no cubriría nada en vez de
cubrir todo (fail-closed vs fail-open). El criterio del equipo es que cubrir todo el ramo **puede
ser un caso de uso válido** —hay coberturas que efectivamente cubren todo—, así que no corresponde
forzar la carga; pero como acá se define con qué suma asegurada se liquida un siniestro, tampoco
puede pasar en silencio. Quedó como aviso en el panel del referente (solapa Coberturas):

- Con la cobertura **cerrada**, un `app-badge` con punto de semáforo `warning`: "Cubre todo el
  ramo". Sin esto había que abrir las coberturas de a una para enterarse.
- Con la cobertura **abierta**, debajo de los chips de hechos generadores, el aviso completo: que no
  excluye ninguno, cuántos cubre, y —lo que importa— que **si una póliza contrata varias coberturas,
  la que no excluye nada es la que termina respondiendo, y el siniestro se liquida con su suma
  asegurada y su franquicia**. Ese es el riesgo concreto que introduce
  `PolicyCoverageResolver` cuando no puede distinguir.

Texto solo, en el color de warning del semáforo, sin fondo saturado: es algo para revisar, no un
error que impida guardar (guardrail del design system — el semáforo comunica estado, sobrio).

**Decidido (01/09/2026): el default se queda en fail-open.** Se evaluó invertirlo a fail-closed —que
una cobertura sin exclusiones cargadas no cubriera nada— y se descartó: una cobertura que no cubre
ningún hecho generador no tiene sentido como entidad de negocio, así que ese default convertiría un
olvido de carga en algo peor que lo que arregla. Una cobertura sin regla sigue cubriendo todo su
ramo, y el aviso de arriba es lo que se apoya para que sea una decisión del referente y no un
descuido. No reabrir sin un caso nuevo.

### Los expedientes ya creados no se tocan — decisión pendiente

Todo lo anterior arregla el alta **de acá en adelante** y los datos de las pólizas. Lo que no toca
son los expedientes que ya existen: `cases.coverage_id` quedó apuntando a la cobertura que el
modelo viejo eligió, y su `policy_snapshot` tiene congelada la suma asegurada de esa cobertura. En
Railway son los tres expedientes de Hurto colgados de Robo de celular.

Recalcularlos no es un `UPDATE`: cambiar la cobertura de un expediente cambia los parámetros con
los que se evaluaron sus reglas duras (carencia, plazo de denuncia, tope de eventos, franquicia,
inclusión de cobertura), o sea que hay que **reclasificarlos** y eso reescribe resultados que un
analista pudo haber mirado ya.

**Decidido (01/09/2026): se dejan como están.** La base es de prueba y en el peor caso se levanta de
cero con `reset → init → seed`, que ya nace con el modelo nuevo — no justifica reclasificar. Si
alguna vez hay que corregirlos sobre datos que importen, el camino limpio es reabrir con motivo,
reclasificar y volver a decidir (punto 11), que deja la traza en `case_status_history`. La consulta
para listarlos está al pie del archivo de migración.

Mientras tanto sigue en pie la mitigación que ya estaba: la solapa de trazabilidad aclara de qué
cobertura es la suma asegurada, así que el número no se lee como si fuera la del hecho denunciado.

### Agotamiento por monto, ahora por cobertura ✅ resuelto (01/09/2026)

Quedaba un cabo suelto del punto 6: `aseguradora.siniestro_historico` no tenía `cobertura_id`, así
que el acumulado sumaba los liquidados **de la póliza**. Con la suma asegurada ya estrechada a la
cobertura que corresponde, eso dejó de ser "conservador" y pasó a ser una comparación mal apareada:
numerador de la póliza contra denominador de una cobertura. En la póliza 1 del seed, un robo
liquidado por 700.000 reportaba la cobertura de hurto (650.000) como agotada sin que se hubiera
denunciado un solo hurto. No rechazaba nada —la regla bloquea Fast Track y aporta motivos— pero le
daba al analista un motivo falso.

Consultado con la analista (01/09/2026): **el monto es por cobertura y no hay tope agregado de
póliza**, y el registro histórico real debería traer la cobertura. Con eso:

- `aseguradora.siniestro_historico` lleva `cobertura_id` (nullable), y `ClaimRecord` lo transporta
  como `coverageName` — por nombre, que es como se puentea todo contra la BD Aseguradora.
- `evaluateSumInsuredLimit` filtra el historial por cobertura además de por póliza. Un previo sin
  cobertura imputada se **saltea** en vez de cargarlo contra la equivocada: la regla queda permisiva
  ante historial incompleto, que es el lado correcto para errar cuando lo único que produce es un
  motivo para el analista.
- Migración `2026-09-01-historico-cobertura.sql`, con backfill por nombre para las causas que hoy lo
  determinan sin ambigüedad. Lo que no se puede determinar queda en NULL.

**Charla pendiente:** la analista marcó que la relación hecho generador ↔ cobertura puede no ser
lineal — una póliza podría tener dos coberturas que respondan por el mismo hecho. Eso no afecta a
esta regla (el histórico trae la cobertura, no se infiere), pero sí al **alta**: ahí
`PolicyCoverageResolver` desempata por el orden de la compañía cuando más de una responde. Las
opciones son que el referente declare una prioridad, que elija el analista, o dejarlo. Va a una
charla aparte.

---

## Resumen priorizado

| # | Tema | Estado | Prioridad |
|---|------|--------|-----------|
| 1 | Interrupción/reanudación del plazo de respuesta | ✅ Resuelto 31/08/2026 | — |
| 1 | Prescripción (1 año, gate determinístico → `LLM_NO_RECOMIENDA_APROBAR`) | ✅ Resuelto 31/08/2026 | — |
| 1 | Caducidad por inacción (18 meses, estado `LAPSED` + sweep) | ✅ Resuelto 31/08/2026 | — |
| 1 | Plazo desde documentación completa (no desde la denuncia) | ✅ Resuelto 31/08/2026 (efecto colateral) | — |
| 6 | Agotamiento de cobertura por monto (suma asegurada) | ✅ Resuelto 31/08/2026 | — |
| 11 | Reapertura de expedientes cerrados (`REHABILITADO`) | ✅ Resuelto 31/08/2026 | — |
| 2 | Estados faltantes restantes (PRESCRIPTO dedicado, DESISTIDO, BAJA POR ERROR, EN MEDIACION/JUICIO) | No existe | Media |
| 4 | Derivación a servicio técnico / cristalería | No existe | Media |
| 12 | Una póliza con varias coberturas (`policy_coverage`) | ✅ Resuelto 01/09/2026 | — |
| 12 | Exclusiones de cobertura del seed (estaban cargadas con un rule_type inexistente) | ✅ Resuelto 01/09/2026 | — |
| 12 | Aviso al referente cuando una cobertura no excluye nada | ✅ Resuelto 01/09/2026 | — |
| 12 | Invertir a lista blanca (cobertura sin regla no cubre nada) | Descartado 01/09/2026 — no reabrir | — |
| 12 | Expedientes ya creados con la cobertura vieja | Se dejan — decidido 01/09/2026 (base de prueba) | — |
| 12 | Agotamiento por monto acumulado por cobertura (histórico con `cobertura_id`) | ✅ Resuelto 01/09/2026 | — |
| 12 | Hecho generador ↔ cobertura no lineal: cómo desempatar en el alta | A charlar | Media |
| 3 | Reservas (SPL) como control | No existe | Baja/a decidir |
| 10 | Consistencia interna de `LAPSED` (carga del analista, tableros, filtro de bandeja, tono al asegurado) | ✅ Resuelto 31/08/2026 | — |
| 5 | Comentario desactualizado en `DocumentRequirement` | ✅ Resuelto 31/08/2026 | — |
| 10 | Test de `LapseSweepScheduler`, gate de prescripción y endpoint de reapertura | ✅ Resuelto 01/09/2026 | — |
| 7, 8 | Fraude, roles, segmento | Ya cubierto o deliberado | — |
