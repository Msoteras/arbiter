# Handoff — Plan de pruebas: alcance real, defectos y qué falta

**Fecha:** 09/08/2026 · **De:** Fede · **Para:** Aylén
**Rama revisada:** `feature/front-details`, commit `0ed76ec` (working tree limpio)
**Disparador:** card de Sprint 7 *"Caso de Prueba - BBVA en Ramo Tecnología Portátil"* (Fast Track
Express / Doc Reducida / Fraudulento con constancia IMEI falsa).

**Cómo se hizo:** lectura de código sobre esa rama. **No se levantó Docker ni se corrió nada contra
Railway en esta sesión** — todo lo que sigue sale del código y de los scripts de `db/`, no de la base
viva. Donde eso importa está marcado.

---

## 1 · Qué se revisó y qué no

| Área | Revisada | Comentario |
|------|----------|------------|
| Alta de denuncia (wizard del asegurado) | ✅ | Componente + `CaseRequest` + resolución de referencias |
| Clasificación (Fast Track, LLM, documentación) | ✅ | Orquestador, validador, adapters de reglas, prompt |
| Scoring de fraude / análisis forense de imágenes | ✅ | Evaluadores + cascada CLIP/pgvector → Vision |
| Motor de reglas (lo que configura el referente) | ✅ | Fast Track, agenda documental, textos, scoring |
| Expedientes: estados, decisión del analista, documentos | ✅ | Transiciones + endpoints + control de acceso |
| Portal del asegurado | ✅ (superficial) | Confirmado que pega a endpoints reales, no mock |
| Reportes y Dashboard del referente | ✅ | Ver §5: no hay backend |
| Auth / alta de usuarios / Auth0 | ❌ | No se tocó en esta pasada |
| Frontend responsive / accesibilidad | ❌ | No se tocó en esta pasada |

---

## 2 · El caso del card: BBVA en Ramo Tecnología Portátil

### 2.1 Punto de partida: BBVA hoy no vende ese ramo

En los scripts versionados, Tecnología Portátil es **de Provincia**:

- `db/seed-demo.sql:112` — la única póliza `'Tecnología Portátil'` está en `aseguradora_provincia`.
  Las 5 de BBVA son todas Celulares.
- `db/init-multitenant.sql:693` — cada tenant arranca con coverages 1 y 2, ambas `branch_id = 1`.
  La cobertura de branch 2 (`Daño accidental`) se la agrega `seed-demo.sql:279` **solo a Provincia**.
- Igual el panel de ramos le muestra "Tecnología Portátil · 2 coberturas" a BBVA, porque la lista sale
  de un mock del front (`arbiter-frontend/src/app/features/admin/rules-config.service.ts:100`).
  La solapa abre vacía. Es lo que quedaba del P3 del handoff de reglas.

**Mínimo para que el caso exista** (5 cosas, todas de datos, ninguna de código):

1. `aseguradora_bbva.poliza` con `rama = 'Tecnología Portátil'` + su `cobertura` con suma asegurada
   (de ahí salen `branch`, `product` e `insuredAmount`).
2. `arbiter_bbva.coverage` con `branch_id = 2`.
3. `arbiter_bbva.policy` apuntando a esa coverage — **el expediente hereda la cobertura de la póliza**,
   no la elige el asegurado (`CaseServiceImpl.java:99`).
4. `arbiter_bbva.insured` con el DNI de quien va a denunciar (Martina o Julián).
5. Config del referente para esa cobertura (Fast Track y textos): el mock no trae entrada para
   coberturas nuevas.

> ⚠️ Mar re-seedeó Railway y Tecnología pasó de branch 3 a **branch 2**. Antes de escribir ids
> concretos en los casos de prueba, confirmar el catálogo real contra Railway.

### 2.2 Límites duros del ramo (aplican a todos los casos)

- **Hecho generador: solo Robo o Hurto.** `CaseReferenceResolver.java:34` resuelve `(ramo, nombre)` y
  tira **422** si no existe. Branch 2 tiene `Daño accidental`, `Robo en vía pública` y `Hurto`; el
  wizard ofrece 4 tipos fijos (`nueva-denuncia.component.ts:133`): Robo ✅, Hurto ✅,
  **Rotura accidental ❌**, **Otro → "Siniestro general" ❌**. O sea: *Daño accidental*, la cobertura
  propia del ramo, **no es alcanzable desde la UI**. → **Resuelto (`7c8dd20`)**: el wizard ahora
  ofrece los hechos generadores reales del ramo desde el backend (falta validar en vivo).
- **No hay slot para la constancia de baja de IMEI.** El asegurado sube 4 tipos
  (`case-document.ts:26`): `police_report`, `item_photo`, `invoice`, `quote`. La agenda documental
  del referente maneja otros 4 (`business-rules.ts:112`): `police_report`, `purchase_proof`,
  `imei_deregistration`, `last_connection`. **Solo `police_report` intersecta.** → **Resuelto
  (`14fce00`)**: vocabulario unificado (el uploader deriva de la agenda del referente),
  `imei_deregistration` ahora es subible (falta validar en vivo).
- **Cierre**: todos los casos terminan en `APPROVED` o `REJECTED` vía el endpoint de decisión
  (`APROBAR`/`RECHAZAR`, `CaseServiceImpl.java:376`), que solo acepta casos en
  `PENDING_ANALYST_REVIEW`.

### 2.3 Decisiones de alcance ya tomadas (Fede, 09/08)

- **"Doc Reducida" no es un concepto nuevo**: es el camino `FALTA_DOCUMENTACION` con
  `AWAITING_DOCUMENTATION` como estado intermedio, y **no es específico de este ramo**.
- **Los dos casos de fraude se diseñan**, el que no está implementado va en rojo con su defecto.
- **El alcance llega hasta la decisión del analista** (no hasta el mail: no existe, ver §5).

### 2.4 Los 6 casos, delimitados

| # | Caso | Recorrido | Estado |
|---|------|-----------|--------|
| 1 | **Fast Track (Express)** | Denuncia → gate determinístico → `PENDING_ANALYST_REVIEW` con `FAST_TRACK` → analista aprueba → `APPROVED` | ✅ Verde **si** el referente habilita Fast Track para esa cobertura. Que ande solo con configuración es, además, el caso de prueba del RNF de mantenibilidad ("ramo nuevo en ≤1 sprint sin tocar código"). |
| 2 | **Falta documentación** | Denuncia incompleta → `AWAITING_DOCUMENTATION` → el asegurado sube → `PENDING_CLASSIFICATION` → reclasifica → analista decide | ✅ El gate ya lee la agenda real del referente (`14fce00`); falta validar en vivo. |
| 3 | **Fraude: adjunto reusado / bajado de la web** | Igual que 1, pero el adjunto ya se usó en otra denuncia → `image_reuse` (o `image_web_match`) suma al score → analista rechaza | ✅ Verde de punta a punta, con informe forense visible. |
| 4 | **Fraude: constancia IMEI fabricada** | Constancia inventada, no reusada, IMEI que no coincide | 🔴 Nada la detecta (**D4b**). Va como caso en rojo. |
| 5 | **Factura de otro equipo** | Factura de un celular distinto al bien asegurado | 🟡 Solo el LLM puede notarlo leyendo el OCR; no es determinístico. El resultado esperado no puede ser "el sistema detecta", sino "el analista tiene el dato a la vista". |
| 6 | **Hurto no cubierto** | Denuncia de Hurto sobre una cobertura que lo excluye | 🟡 Termina en `REJECTED` **por criterio del analista, no por regla** (**D3**). Sirve justamente para documentar el gap. |

---

## 3 · Defectos y gaps encontrados

Severidad según los criterios del plan de pruebas (Crítico / Alto / Medio / Bajo).

### 🔴 Críticos

**D1 · Cualquier asegurado puede subir documentos al expediente de otro**
`POST /cases/{id}/documents` es `hasAnyRole('ASEGURADO','REFERENTE_ASEGURADORA')`, y
`addDocumentsAndReclassify` (`CaseServiceImpl.java:120`) hace `findById` y nada más: **no pasa por
`CaseAccessPolicy`**. Los *reads* sí están cerrados (`readableCase`, línea 366), este *write* no.
Impacto: subir documentación a un expediente ajeno y forzarle una reclasificación.
*Se prueba con Postman en dos minutos: dos asegurados, el id de expediente del otro.*

**D2 · El alta de denuncia no valida a nombre de quién se denuncia**
En `createCase`, `insuredId` (DNI) y `policyNumber` salen del payload y se resuelven por separado
(`CaseReferenceResolver`), sin compararse contra el usuario del JWT **ni entre sí**. Se puede
denunciar sobre la póliza de otro, o combinar el DNI de uno con la póliza de otro. El único uso de
`CallerContext` en ese servicio es para resolver la aseguradora (línea 219).

### 🟠 Altos

**D3 · Nada valida que el hecho generador esté cubierto por la cobertura**
El expediente hereda la cobertura de la póliza (`CaseServiceImpl.java:99`) y no hay chequeo alguno.
Denunciar Hurto sobre una cobertura que excluye el hurto entra igual y llega a la bandeja.

**D4 · Las exclusiones y reglas de negocio son texto, no reglas — y no queda auditoría**
- **D4a**: tanto las **exclusiones** (`rules.exclusions()`) como las **reglas de negocio en texto**
  (`rules.rules()`) que escribe el referente se pegan como texto en el **prompt del LLM**
  (`PromptBuilder.renderRulesAndPolicy`; `RulesRestAdapter` las superpone desde la DB) y ahí termina
  su vida: el único que las "aplica" es el modelo, interpretándolas — **no hay código que las
  evalúe**. Consecuencia: **no determinístico** (la misma denuncia puede clasificar distinto entre
  corridas) y **sin auditoría** de qué regla se evaluó y con qué resultado. Contradice la implicancia
  #4 del `CLAUDE.md` (las exclusiones de cobertura son **reglas evaluables en rules-service**, no
  decisiones del LLM).
  - **Matiz**: muchas son *duras-eables* y deberían evaluarse en código — plazo de denuncia (fechas),
    póliza al día (estado de pago), monto dentro del límite (comparación), "hurto sin violencia"
    (exclusión dura). Solo las genuinamente *interpretativas* (relato inconsistente, daño no
    relacionado con el robo) justifican quedar en el prompt. **Diseño objetivo**: evaluar las duras
    en el motor + escribir `rule_result` (ver §7.1), y dejar al LLM solo las interpretativas.
- **D4b**: `DocumentInconsistencyEvaluator` (el factor que agarraría "el IMEI del documento no coincide
  con el del bien") es un **stub** que se declara no evaluable.
- **D4c**: `rule_result` —la tabla donde se auditaría qué regla se evaluó y con qué resultado— tiene
  entidad y repository y **cero escritores**. Las filas de la demo las puso el seed a mano. Es
  requisito de la Disposición SSN 2/2023.

**D5 · La clasificación del LLM es texto puro: no recibe la imagen** — *fecha/monto/lugar RESUELTO (Mar, v2)*
- ✅ **Resuelto**: la **fecha del hecho, el lugar y el monto reclamado** ya viajan al prompt. Se
  agregaron a `ClassificationRequest`, se mapean en `ClassificationOrchestrator.buildRequest()` desde
  el `ClaimReport`, y se imprimen en el template — renombrado a **`classification-v2.md`** con
  `getPromptVersion()` = `"classification-v2"` para no romper la auditoría de
  `llm_analysis.prompt_version` (Disposición SSN 2/2023). Implementado, **sin validar en vivo**.
- ⚠️ **Queda abierto**: el modelo de **clasificación no recibe la imagen**, solo su OCR — el modelo
  de visión la ve en la extracción, pero la clasificación es texto puro. Para casos de fraude
  documental *visual* (constancia fabricada) esto limita lo que el LLM puede notar.

**D19 · El `InsurerDatabaseAdapter` no se migró a multi-tenant** — *detectado 10/08 (Mar)*
Lee el schema **bare `aseguradora`** (`InsurerDatabaseAdapter.java:35`), que es del modelo
single-schema viejo (`db/datos-aseguradoras.sql`), **no** los `aseguradora_<tenant>` que crea el seed
multi-tenant (`init-multitenant.sql` → `create_insurer_db_schema('aseguradora_bbva'…)`). Encima la
query hace `JOIN … c.id = p.aseguradora_id` (`:60`), pero en el modelo multi-tenant la `poliza` ya
**no tiene** `aseguradora_id` (el discriminador se dropeó; `compania` es una sola fila por schema).
Consecuencia: tras un reseed multi-tenant limpio (reset→init→seed, **sin** `datos-aseguradoras.sql`),
`/policies` y `/cases` tiran **500** (`relation "aseguradora.poliza" does not exist`). Hoy solo anda
si sobrevive el schema `aseguradora` viejo; los `aseguradora_bbva`/`aseguradora_provincia` que llena
el seed multi-tenant **no los lee nadie**.
- **Fix**: migrar el adapter a leer `aseguradora_<tenant>` según el `TenantContext` (la aseguradora
  correspondiente al asegurado) y reescribir la query — sacar `p.aseguradora_id`, `compania` es la
  fila única del schema. A decidir: la vista multi-aseguradora (Martina es cliente de BBVA y
  Provincia con el mismo DNI, §6.2) — ¿solo el tenant actual, o unir todos los `aseguradora_*`?
- **Workaround hasta migrarlo**: después del reset trio, correr también `db/datos-aseguradoras.sql`
  (recrea el schema `aseguradora` bare que el adapter todavía necesita).

### 🟡 Medios

**D9 · Cuatro columnas de `coverage` que no lee nadie**
`waiting_period_days` (carencia), `claim_exhausts_coverage`, `covers_family_group`, `is_individual`:
cero consumidores, ni siquiera para mostrarlas.

**D10 · La regla estrella del ramo no se aplica**
`max_eventos_anuales` y `segundo_evento_pct` ("2 eventos por año y póliza, el segundo al 50%") se
guardan, se muestran en el CRUD del referente y no los evalúa nada.

**D11 · El plazo de denuncia no se evalúa**
`report_deadline_hours` (72 hs en Celulares, 96 en Tecnología) es editable por el referente y solo se
lee para devolverlo en el CRUD (`CoverageService.java:91`).

**D12 · `police_report_at` no se captura ni se evalúa**
Existe en `CaseRequest` y en la entidad, pero **el wizard nunca lo manda** (ver el payload de
`nueva-denuncia.component.ts:260`) y ningún servicio lo lee. La regla "denuncia policial dentro de las
48 hs", que está escrita en el mock de reglas, es hoy literalmente inverificable.

**D13 · Vigencia de la póliza vs fecha del hecho: no se valida**
`effectiveFrom`/`effectiveTo` se leen de la BD Aseguradora y se imprimen en el prompt. Nadie valida
que el siniestro haya ocurrido dentro de la vigencia.

**D14 · Criterios de Fast Track que la UI promete y el motor no tiene**
`minPolicyAgeMonths` y `priorClaimsWindowMonths` están en el mock del front
(`rules-config.service.ts:138`) y **no existen** en `FastTrackConfigDto` ni en
`BusinessRules.FastTrackThresholds`, que tienen 4 campos. Un caso de prueba tipo "póliza con menos de
6 meses de vigencia → no aplica Fast Track" hoy falla.

**D15 · El panel de ramos sale de un mock**
No hay CRUD de `Branch`; la lista de ramos y sus coberturas es el `SEED_RAMOS` del front. Por eso le
muestra Tecnología Portátil a BBVA. (P3 del handoff de reglas.)

### 🔵 Bajos

**D16 · PEP se pide, se guarda y no lo usa nadie**
Ni scoring, ni prompt, ni pantalla del analista. Cero consumidores.

**D17 · La advertencia "los pesos deberían sumar 100%" contradice al motor**
`RiskScoringService` normaliza (`score = weightedSum / totalWeight`): los pesos son **relativos**. El
seed suma 190% y la UI lo marca como error de fábrica. *(Detectado en la sesión anterior, no
re-verificado ahora.)* Decidir con Mar/Valen: sacar la regla o ajustar el seed.

**D18 · Dos tests rotos desde antes**
`ClassificationOrchestratorIntegrationTest.lowAmountFirstClaimUpToDate_shouldFastTrack…` (arma un
`ClaimReport` sin `coverageId`, quedó viejo respecto del scoping por cobertura del PR #29) y
`RulesServiceApplicationTests.contextLoads` (no encuentra datasource). *(Verificado con `git stash`
contra la base limpia en la sesión anterior.)*

---

## 4 · Estado de los defectos

| ID | Severidad | Estado | Dueño |
|----|-----------|--------|-------|
| D1, D2 | Crítico | Abierto — sin dueño | ¿historia? |
| D3, D4a/b/c | Alto | Abierto — sin dueño | ¿historia? |
| D5 | Alto | fecha/monto/lugar ✅ (v2, sin validar en vivo); falta la imagen al LLM | Mar |
| D19 | Alto | Abierto | Mar (rumbo definido) |
| D9–D15 | Medio | Abierto | — |
| D16–D18 | Bajo | Abierto | — |

---

## 5 · Fuera de alcance: declarado pero no implementado

**No son defectos** — son funcionalidad que todavía no se construyó. Importa tenerlo escrito para que
el plan de pruebas no liste como componente algo que no existe:

- **Notificaciones al asegurado.** La decisión #9 dice SendGrid en cada cambio de estado.
  `Notification` tiene entidad y repository con **cero escritores**; SendGrid solo se usa en
  `auth-service` para invitar usuarios.
- **Plazo legal de respuesta.** `response_deadline` se calcula al crear el expediente (30 días,
  Ley 17.418 art. 56) y **nunca se vuelve a leer**. Nada avisa cuando vence — y el silencio de la
  aseguradora equivale a aceptación.
- **Reportes y Dashboard del referente.** `reports-service` tiene solo la entidad `Metric` y su
  repository: **sin controllers, sin services**. Las dos pantallas dicen "TODO MOCK" en su propio
  encabezado. → **Marcar explícitamente fuera de alcance de esta iteración de testing.**
- **Detección de fraude documental.** Hoy la cascada cubre reuso interno (CLIP + pgvector) y match web
  (Google Vision). Un documento *fabricado* no dispara ninguno de los dos.

---

## 6 · Qué se puede testear ya (batería sugerida)

Ordenado por lo que más rinde:

1. **Seguridad y permisos de acceso** — D1 y D2 son casos de prueba redondos, con Postman, severidad
   Crítica. Sumar: lectura de expediente ajeno (esto **sí** está bien cerrado: 404, no 403, para no
   filtrar existencia), descarga de documento ajeno (también cerrado), y el matriz de roles sobre los
   endpoints de `CaseController` (`@PreAuthorize` por endpoint).
2. **Portal del asegurado** — es real, no mock. Ver solo los propios (incluidos los de las dos
   aseguradoras: Martina es cliente de BBVA y Provincia con el mismo DNI), subir documentación
   faltante y verificar que el expediente vuelva a `PENDING_CLASSIFICATION` y se reclasifique,
   seguimiento de estados con su timeline.
3. **Validaciones de alta** — batería de negativos barata y real: `@PastOrPresent` en la fecha del
   hecho, `@NotBlank` en 7 campos, `@NotNull` en PEP y consentimiento de imagen, archivo > 10 MB,
   archivo que no es imagen ni PDF, y los 422 de referencias no resolubles.
4. **Los 6 casos del ramo** (§2.4), con los rojos documentados como defecto.
5. **Fast Track configurable** — el mismo caso 1 sirve para probar el RNF de mantenibilidad: ramo
   nuevo operativo **solo con configuración del referente**, sin deploy.
6. **Regresión multi-tenant** — aislamiento entre esquemas con la identidad compartida de Martina. Ya
   se verificó en vivo en agosto; como caso escrito vale para cada release.

---

## 7 · Historias candidatas (a decidir si se crean)

1. **Validación de reglas duras de cobertura** — cubre D3 + D4a + D4c: que el motor evalúe cobertura
   vs hecho generador y las exclusiones, y **escriba en `rule_result`**. Cierra además el requisito de
   auditoría de la Disposición 2/2023. Es la más valiosa de la lista.
2. **Cerrar los dos agujeros de autorización** — D1 y D2. Chico en código, Crítico en severidad.
3. **Reglas temporales y de frecuencia** — D10, D11, D12, D13: plazo de denuncia, vigencia al momento
   del hecho, tope de eventos anuales. Todas necesitan lo mismo: que alguien evalúe fechas.
4. **Alerta de vencimiento del plazo legal** — §5, la de consecuencia legal más concreta.
5. **CRUD de ramos** — D15, para que el panel deje de mentir.

---

## 8 · Notas operativas

- **Catálogo re-seedeado**: Tecnología pasó de branch 3 a **2**. Confirmar ids contra Railway antes de
  fijarlos en los casos de prueba.
- **La sesión vive en memoria**: cualquier recarga completa (HMR al editar, o navegar por URL en vez
  de por los links del nav) desloguea.
- **JDK 21 explícito** para compilar (`JAVA_HOME` del sistema resuelve a 26 y rompe Lombok).
- **Se trabaja contra Railway** con `scripts/run-local.ps1 <módulo>`, nunca contra el Postgres local
  del `docker-compose.yml`. Si Railway se satura de conexiones, el único remedio es reiniciar el
  servicio desde el panel (corta a todo el que esté conectado — avisar antes).
- **Template de casos de prueba** (columnas del `.xlsm` de la cátedra): Componente Funcional · Caso de
  Uso · # Caso de Prueba · Caso de Prueba · Regresión · Descripción · Precondiciones · Datos de
  Entrada · Pasos · Resultado Esperado · Diseñador · Fecha ‖ Resultado · Severidad · Prioridad ·
  Observaciones del Resultado · Tester.
- **Tests con Testcontainers**: sufijo `*Tests.java`, nunca `*IT.java` (Surefire los excluye en
  silencio).
