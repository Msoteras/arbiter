# Caso de prueba — Fast Track (Express) · BBVA · Ramo Celulares

Caso de referencia del **gate determinístico**: la denuncia pasa las tres compuertas que gobiernan
la vía expedita, así que se resuelve sin invocar al LLM y el expediente llega al analista listo
para decidir.

Es el escenario "todo bien" del módulo: la línea de base contra la cual se leen los otros cuatro
veredictos. Si este falla, el problema está en el gate o en el entorno, no en el modelo.

> **Recordatorio de diseño:** Fast Track **no** resuelve el siniestro. Es la única de las cinco
> clasificaciones que no sale del LLM, pero igual requiere aprobación del analista
> (decisión #5 de arquitectura). Agiliza, no automatiza.

---

## 1 · Datos del caso

| Campo | Valor | De dónde sale |
|---|---|---|
| Aseguradora | BBVA Seguros Argentina S.A. (`insurerId` 1, esquema `arbiter_bbva`) | `arbiter_common.insurer` |
| Asegurada | Martina Soteras — DNI `42.987.654` | `aseguradora_bbva.asegurado` |
| Póliza | `POL-CEL-2026-042` — Celular Protegido Premium | `aseguradora_bbva.poliza` |
| Ramo / Hecho generador | Celulares (id 1) / **Robo en vía pública** (id 2) | `arbiter_common.claim_cause` |
| Cobertura | `Robo de celular` (id 1) | `arbiter_bbva.coverage` |
| Bien asegurado | Samsung Galaxy A56 5G 256 GB — IMEI `356938035643809` | denuncia |
| Suma asegurada | **1.300.000** | `aseguradora_bbva.cobertura` |
| Estado de pago | `AL_DIA`, sin saldo deudor | `poliza.estado_pago` |
| Siniestros previos | **0** | `aseguradora_bbva.siniestro_historico` |
| Monto reclamado | **620.000** | denuncia |
| Fecha del hecho | **ayer, 19:25** (relativa — ver §3) | generador de fixtures |

El monto es coherente con el bien —620.000 es precio de mercado plausible para un A56 de 256 GB— y
a la vez cae dentro del umbral. No está elegido para "hacer pasar" el test: es el caso realista que
el umbral está pensado para dejar pasar.

## 2 · Por qué tiene que dar `FAST_TRACK`

No alcanza con los umbrales. El orquestador exige que **tres evaluadores** den verde a la vez
([ClassificationOrchestrator:235](../../classification-service/src/main/java/ar/edu/utn/frba/arbiter/classification/services/ClassificationOrchestrator.java#L235)):

```java
if (fastTrack.fastTrack() && !temporal.blocksFastTrack() && !scope.blocksFastTrack())
```

y antes de llegar ahí ya se descartaron la exclusión de cobertura y la falta de documentación.

### 2.0 · Precondiciones (si fallan, ni se evalúa Fast Track)

| Compuerta | Qué pide | Este caso |
|---|---|---|
| `CoverageRuleEvaluator` — exclusión de cobertura | el hecho generador no puede estar en la lista negra de la cobertura | la cobertura 1 excluye **Hurto** (id 3); acá es **Robo** (id 2) ✅ |
| Agenda documental | los 4 documentos del ramo, adjuntos | los 4 van en el request ✅ |

### 2.1 · Los umbrales — `FastTrackValidator`

Cinco criterios; cada uno se evalúa solo si está configurado (`null` = no aplica):

| Umbral | Configurado | Este caso | ¿Pasa? |
|---|---|---|---|
| `maxClaimedAmountRatio` | ≤ 0,50 | 620.000 / 1.300.000 = **0,477** | ✅ |
| `maxPriorClaims` (+ ventana) | ≤ 0, sin ventana | **0** previos | ✅ |
| `minPolicyAgeMonths` | no configurado | — | no evalúa |
| `requiresUpToDatePolicy` | `true` | póliza **`AL_DIA`** | ✅ |
| `requiredDocumentTypes` | `["police_report"]` | denuncia policial adjunta | ✅ |

Margen deliberadamente chico en el ratio (47,7% contra un techo de 50%): si alguien mueve el umbral
o cambia la suma asegurada, el caso lo acusa. Para el caso espejo que **no** debe pasar, subir el
monto a 700.000 (53,8%) y verificar que caiga al LLM.

### 2.2 · Los bloqueantes temporales — `TemporalRuleEvaluator`

Cualquiera que falle bloquea el Fast Track, aunque los cinco umbrales hayan pasado:

| Regla | Límite | Este caso | ¿Pasa? |
|---|---|---|---|
| **D13** vigencia de la póliza | el hecho dentro de `effectiveFrom..effectiveTo` | póliza 2026 vigente | ✅ |
| **D9** carencia | 30 días desde el alta | hecho a +7 meses | ✅ |
| **D12** plazo de la denuncia **policial** | 72 hs desde el hecho | **2 h 15 min** | ✅ |
| **D11** plazo de la denuncia **a la aseguradora** | 72 hs (`coverage.report_deadline_hours`) | 24–49 hs (ver §3) | ✅ |
| **D10** tope de eventos por año | 2 | este sería el 1.º | ✅ |

### 2.3 · El alcance de la cobertura — `CoverageScopeEvaluator`

| Regla | Configurado | Este caso | ¿Pasa? |
|---|---|---|---|
| `covers_family_group` | `false` — la cobertura no alcanza al grupo familiar | la damnificada es la titular | ✅ |
| `claim_exhausts_coverage` | `false` en la cobertura 1 | la regla no participa | ✅ |

## 3 · Los fixtures y por qué se regeneran

```
docs/postman/test-docs/
├── generar-fixtures.js                    ← genera todo lo de abajo
├── foto_equipo_para_fraude.jpg            → parte multipart  item_photo   (opcional, ver §3.3)
└── fraude/fast-track/
    ├── caso_fast_track.json               → parte multipart  case
    ├── denuncia_policial_fast_track.pdf   → parte multipart  police_report
    ├── factura_compra_fast_track.pdf      → parte multipart  purchase_proof
    ├── baja_imei_fast_track.pdf           → parte multipart  imei_deregistration
    └── ultima_conexion_fast_track.pdf     → parte multipart  last_connection
```

```bash
node docs/postman/test-docs/generar-fixtures.js
```

Cómo funciona el generador, y cómo sumar un escenario propio reusando el motor: [README de fixtures](../postman/test-docs/README.md).

**El set caduca a las 72 hs y hay que regenerarlo.** `cases.reported_at` es `@CreationTimestamp`
—el momento en que se crea el expediente— y la regla D11 compara `reportedAt − eventDate` contra
las 72 hs de la cobertura. Con una fecha de hecho fija, el fixture deja de dar `FAST_TRACK` tres
días después de escrito, sin que nada avise. Por eso el generador ancla el hecho a **ayer a las
19:25**: fecha relativa, hora fija, siempre entre 24 y 49 hs antes de la corrida. El script imprime
la fecha exacta de vencimiento al terminar.

Los cinco archivos salen de un **único objeto `CASE`**, así que la coherencia entre el payload y los
documentos está garantizada por construcción y no por copiar a mano. El IMEI, el DNI, la línea, el
modelo y las fechas son los mismos en todos.

### 3.1 · Convención de nombre

`<qué_es>_<escenario>.<ext>` — prefijo = el documento, sufijo = el escenario de prueba
(`fast_track`, `ambigua`, …), igual que el `denuncia_policial_ambigua.pdf` que ya existía.

**El nombre de archivo no lo lee el backend.** Lo que el sistema usa es el **nombre de la parte
multipart** (`police_report`, `purchase_proof`, `imei_deregistration`, `last_connection`,
`item_photo`). El nombre del archivo es para que un humano encuentre el fixture.

### 3.2 · Los cuatro PDFs

Una página cada uno, A4, texto seleccionable (Helvetica/WinAnsi), con leyenda al pie que los
identifica como documentos simulados de prueba. Empresas ficticias a propósito: no queremos
comprobantes que aparenten ser de una compañía real.

| Parte | Documento | Qué aporta |
|---|---|---|
| `police_report` | Acta de denuncia, Comisaría Vecinal 3-B, actuación 3B-2026-014782 | el hecho; es el único que el gate exige |
| `purchase_proof` | Factura B de un comercio, con IMEI y n.º de serie | titularidad del equipo |
| `imei_deregistration` | Constancia de bloqueo y baja de IMEI de la operadora | el equipo quedó inutilizable |
| `last_connection` | Constancia de último registro en red, con celda y horarios | corrobora hora y lugar del hecho |

Los cuatro se encadenan: el equipo se desconecta de la red 6 minutos después del robo, el bloqueo se
pide 50 minutos después, la denuncia policial 2 h 15 min después, y las constancias de la operadora
se emiten a la mañana siguiente citando el número de actuación.

Que sean de 1 sola página importa: `OllamaDocumentAnalyzer` rasteriza el PDF a 150 DPI y manda
**cada página** al modelo de visión. Un PDF de 5 páginas son 5 inferencias.

### 3.3 · La foto: opcional, y no inocente

`foto_equipo_para_fraude.jpg` es una foto real de un Samsung Galaxy A56 5G (1280×2276, ~600 KB),
tomada a mano sobre una mesa — como llega la foto de un asegurado, no un render de prensa.
Wikimedia Commons, [`File:SmsnGlxA565gBack2026040500.jpg`](https://commons.wikimedia.org/wiki/File:SmsnGlxA565gBack2026040500.jpg),
autor OnionBulb, CC BY-SA 4.0.

**No participa de ninguna de las dos listas de documentos**, así que no influye en el veredicto. Lo
único que hace es disparar la cascada forense (`ImageFraudAnalysisService`): embedding CLIP →
búsqueda de reuso en pgvector → si no hay match interno, escala a Google Vision. Eso alimenta los
factores `image_reuse` (peso 0,50) e `image_web_match` (0,40), que van al **score de fraude
paralelo** y no a la clasificación.

> ⚠️ Al venir de la web, Vision la va a encontrar publicada y `image_web_match` va a puntuar alto.
> El expediente va a seguir siendo `FAST_TRACK`, pero con un `riskScore` inflado. **Para la corrida
> canónica de este caso, no la adjuntes.** Sirve cuando lo que se quiere ejercitar es la cascada
> forense, y ahí el web-match alto pasa a ser el resultado esperado en vez de ruido.

## 4 · Cómo correrlo

Prerequisitos: `auth-service` (8080), `cases-service` (8083), `classification-service` (8082),
**`rules-service` (8081)**, Ollama con `qwen3-vl` y el sidecar CLIP.

`rules-service` no es opcional: si no responde, `RulesRestAdapter` cae al baseline del mock, que
pide un solo documento en vez de cuatro. El caso "pasaría" por una caída de servicio en vez de por
estar bien armado.

```bash
node docs/postman/test-docs/generar-fixtures.js

TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"asegurado.arbiter@gmail.com","password":"asegurado.arbiter123"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

FT=docs/postman/test-docs/fraude/fast-track

curl -X POST http://localhost:8083/api/v1/cases \
  -H "Authorization: Bearer $TOKEN" \
  -F "case=<$FT/caso_fast_track.json;type=application/json" \
  -F "police_report=@$FT/denuncia_policial_fast_track.pdf;type=application/pdf" \
  -F "purchase_proof=@$FT/factura_compra_fast_track.pdf;type=application/pdf" \
  -F "imei_deregistration=@$FT/baja_imei_fast_track.pdf;type=application/pdf" \
  -F "last_connection=@$FT/ultima_conexion_fast_track.pdf;type=application/pdf"
```

Dos detalles del `curl` que cuestan una tarde si no se saben:

- El `case` va **desde archivo** (`=<`, no `=@`): con `@` curl lo manda como parte de archivo y
  Spring lo bindea al `Map<String, MultipartFile> documents` en vez de al `@RequestPart("case")`.
- Y **desde archivo**, no inline: los acentos de `"Robo en vía pública"` escritos en la línea de
  comandos se mandan en la codificación de la consola y el backend responde
  `400 — Invalid UTF-8 middle byte`.

Responde `202 Accepted` con el id. Después se poletea hasta que salga de `PENDING_CLASSIFICATION`:

```bash
curl -s "http://localhost:8083/api/v1/cases/$ID" -H "Authorization: Bearer $TOKEN"
```

## 5 · Resultado esperado

| Qué mirar | Valor esperado |
|---|---|
| `status` | `PENDING_ANALYST_REVIEW` |
| `analysisClassification` | `FAST_TRACK` |
| `analysisConfidence` | `1.0` (el gate es determinístico) |
| Factores | los motivos del validador, uno por umbral evaluado |
| Fila en `llm_analysis` | **ninguna** — por diseño Fast Track no deja fila ahí (ver bug #8 del [plan de pruebas](../plan-pruebas-multitenant.md)) |
| `cases.was_fast_track` | `true` — de acá sale la clasificación que se muestra |
| `riskScore` | poblado; banda baja si **no** se adjuntó la foto |

En los logs de `classification-service`, el gate se anuncia explícito y sin pasar por el modelo:

```
[Orchestrator] Deterministic Fast Track — Reasons=[...]
```

Que **no** aparezca `Not Fast Track (fastTrack=…, temporalBlock=…, scopeBlock=…)` es parte del
assert: ese log dice además **cuál** de los tres evaluadores lo tumbó, así que si el caso falla,
empezar por ahí.

Cierre del ciclo (opcional): el analista aprueba y el expediente pasa a `APPROVED`.

```bash
curl -X POST "http://localhost:8083/api/v1/cases/$ID/decision" \
  -H "Authorization: Bearer $TOKEN_ANALISTA" -H 'Content-Type: application/json' \
  -d '{"analystId":"...","decision":"APROBAR"}'
```

---

## 6 · Estado de la verificación

**El caso está armado y validado en frío; la corrida de punta a punta sigue sin cerrarse.**

### Verificado

- **Los PDFs son procesables por el pipeline real.** Los cuatro cargan en PDFBox 3.0.3 —la misma
  librería que usa `OllamaDocumentAnalyzer`—, rasterizan a 150 DPI y su capa de texto se extrae
  completa, con acentos.
- **Coherencia cruzada del set**, verificada por extracción de texto: el IMEI aparece en los 4 PDFs,
  el DNI en 5 lugares, la línea en 4, el modelo en 4, y el número de actuación policial en los 2
  documentos que corresponde.
- **El contrato multipart funciona.** `POST /api/v1/cases` responde `202 Accepted` y persiste el
  expediente en `arbiter_bbva` con asegurada, póliza y monto correctos.
- **El orquestador llega al gate con los datos correctos**, según los logs:
  `Policy OK — upToDate=true insuredAmount=1300000`, `History OK — previous_claims=0`, `Rules OK`.
- **La lógica del gate está en verde:** `FastTrackValidatorTest`, 7/7, incluido el borde exacto en 50%.

### No verificado

`analysisClassification = FAST_TRACK` + `status = PENDING_ANALYST_REVIEW` **sobre un expediente
real**. Ollama nunca devolvió el OCR de la denuncia: `qwen3-vl` ocupa 6,1 GB y el host de desarrollo
(15,2 GB) no lo sostiene junto a los 5 JVM y el sidecar CLIP. La VM de WSL colapsó por memoria y se
llevó puestos `ollama` y `clip-embedding`, que a diferencia de los módulos **no tienen `restart:` en
el compose** y no vuelven solos.

Para cerrarlo: apagar los módulos que no participan, cerrar navegadores, y no dejar suspender la
máquina — al despertar, el reloj de la VM salta, los JWT nacen vencidos y los pools de Hikari quedan
con conexiones muertas contra Railway (se arregla reiniciando los módulos).

### Un desvío del entorno que sigue abierto

**`InsurerDatabaseAdapter` no habla con la BD real.** El código —en `classification-service`, en
`cases-service` y en todas las ramas, incluida `develop`— consulta `aseguradora.poliza` y joinea por
`p.aseguradora_id`. La base de Railway tiene `aseguradora_bbva` y `aseguradora_provincia`, y en un
esquema por tenant la columna `aseguradora_id` ya no existe (mismo razonamiento que el bug #3 del
[plan de pruebas](../plan-pruebas-multitenant.md)). Con el perfil `insurer-db` la clasificación
revienta con `relation "aseguradora.poliza" does not exist`, y `GET /policies` devuelve 500.

Mientras no se resuelva, el caso corre con `CLASSIFICATION_PROFILES=default` (`MockInsurerAdapter`),
que para `POL-CEL-2026-042` trae los mismos números que la fila real: suma 1.300.000, al día, 0
previos. Único desvío: el mock llama a la asegurada **"Sofía Martínez"** en vez de Martina Soteras, y
`insuredName` del expediente se pisa con el nombre de la póliza — así que el expediente va a mostrar
un nombre distinto al de los PDFs. Es artefacto del mock, no del caso.
