# Caso de prueba — Tecnología Portátil · Robo en vía pública

Set de documentos para armar casos de prueba del **ramo 2 (Tecnología Portátil)**, hermano del set
de [Celulares](caso-prueba-fast-track-celulares.md). Cuatro PDFs + el payload del alta, todos
generados desde un único objeto para que no puedan divergir entre sí.

```bash
node docs/postman/test-docs/generar-fixtures-tecnologia.js
```

Cómo funciona el generador, y cómo sumar un escenario propio reusando el motor: [README de fixtures](../postman/test-docs/README.md).

```
docs/postman/test-docs/
├── generar-fixtures-tecnologia.js         ← genera todo lo de abajo
└── fraude/tec-portatil/
    ├── caso_tecnologia.json               → parte multipart  case
    ├── denuncia_policial_tecnologia.pdf   → parte multipart  police_report
    ├── factura_compra_tecnologia.pdf      → parte multipart  purchase_proof
    ├── bloqueo_equipo_tecnologia.pdf      → parte multipart  imei_deregistration
    └── ultima_conexion_tecnologia.pdf     → parte multipart  last_connection
```

> **El nombre del archivo no lo lee el backend.** Lo que el sistema usa es el **nombre de la parte
> multipart** de la columna derecha. Por eso `bloqueo_equipo_tecnologia.pdf` viaja como
> `imei_deregistration` aunque una notebook no tenga IMEI: el slot es ese.

---

## 1 · Por qué el hecho generador es robo y no daño accidental

La agenda documental del ramo 2 hoy pide **los mismos cuatro documentos que Celulares**
([init-multitenant.sql:738-741](../../db/init-multitenant.sql#L738-L741)), y esos cuatro solo se
sostienen juntos si hubo sustracción: en un daño accidental no existe ni la denuncia policial ni una
constancia de última conexión. El set se armó **para la agenda que hay**, sin reconfigurarla.

Además la agenda es **por ramo, no por hecho generador**: el panel del referente escribe la misma
lista para los tres hechos generadores del ramo
([DocumentRequirementService.java:59-77](../../rules-service/src/main/java/ar/edu/utn/frba/arbiter/rules/services/DocumentRequirementService.java#L59-L77)).
No se puede pedir presupuesto de reparación para daño accidental y denuncia policial para robo sin
tocar código.

## 2 · En qué se diferencia del set de Celulares

No es solo el IMEI. Son cinco diferencias, y las dos primeras cambian **qué evalúa el sistema**:

| # | Celulares | Tecnología Portátil | Consecuencia |
|---|---|---|---|
| 1 | El bien tiene IMEI y línea telefónica | Se identifica por **nº de serie** + **MAC de Wi-Fi** | `DocumentInconsistencyEvaluator.checkImei` **no participa**: `poliza.imei` es NULL en el ramo, así que no hay cruce documento ↔ bien asegurado. Un cruce de fraude menos ([evaluador](../../classification-service/src/main/java/ar/edu/utn/frba/arbiter/classification/services/risk/evaluators/DocumentInconsistencyEvaluator.java#L76-L85)) |
| 2 | Plazo de denuncia de la cobertura: **72 hs** | **96 hs** (cobertura `Daño accidental` del ramo) | El set caduca un día más tarde. La regla D11 compara `reportedAt − eventDate` contra ese plazo |
| 3 | Los dos últimos documentos los emite la **operadora móvil** | Los emite un **servicio técnico autorizado** | Otro emisor, otro vocabulario, otros números de constancia |
| 4 | El equipo se inutiliza **bloqueando el IMEI en la red móvil** | Se inutiliza **bloqueándolo contra la cuenta del fabricante** y registrando el nº de serie | Lo que acredita el documento es lo mismo; el mecanismo que describe, no |
| 5 | La última conexión es a una **antena** (celda, LAC/CID) | Es a una **red Wi-Fi** (SSID, IP pública, radio estimado) | Sin GPS ni celda: la ubicación es aproximada por IP |

Y todo lo circunstancial también cambia, para que los dos casos no se confundan al leer los logs:
otro bien (MacBook Air M3 15" contra Samsung A56), otra póliza, otro barrio, otra comisaría, otra
hora del día (22:10 contra 19:25) y otras empresas ficticias.

## 3 · Para qué sirve cada PDF

### `police_report` — Acta de denuncia · `denuncia_policial_tecnologia.pdf`

**Es el único de los cuatro que participa del gate determinístico.** El Fast Track lo pide por
nombre (`requiredDocumentTypes: ["police_report"]`) y además exige que **se le haya podido extraer
texto**: si el OCR devuelve vacío, el Fast Track se cae aunque el archivo esté adjunto
([FastTrackValidator.java:122-135](../../classification-service/src/main/java/ar/edu/utn/frba/arbiter/classification/services/FastTrackValidator.java#L122-L135)).

Aporta: el hecho, la carátula penal (robo, art. 164), la fecha y hora de la denuncia, el lugar y el
relato de la damnificada. Es el documento que ancla todo el resto.

Qué se puede mutar para un caso negativo:

- **Sacarlo del multipart** → `FALTA_DOCUMENTACION` antes de llegar al Fast Track, y ningún
  documento se analiza (la clasificación corta ahí).
- **Fechar el acta un día distinto del `policeReportAt` del payload** → `checkDeclaredPoliceReportDate`
  suma un hallazgo al factor `document_inconsistency`. Es la diferencia entre *denunciar tarde* (eso
  lo evalúa la regla del plazo) y *declarar una fecha que el papel no respalda*.
- **Correr el hecho a más de 72 hs antes de la denuncia policial** → D12 bloquea el Fast Track.

### `purchase_proof` — Factura de compra · `factura_compra_tecnologia.pdf`

Acredita **titularidad y preexistencia**: que el bien existía y era de ella antes del hecho. Trae el
nº de serie, que es lo que lo ata al equipo denunciado.

Es el único documento cuyo **importe** se cruza automáticamente: `checkAmount` compara el monto de
cada documento contra el `claimedAmount` del payload con un 10% de tolerancia. En este set la
factura es de $2.100.000 y el reclamo de $1.980.000 — 5,7% de diferencia, adentro.

Qué se puede mutar:

- **Subir o bajar el total más de un 10%** respecto del reclamo → hallazgo de `document_inconsistency`.
- **Cambiarle el nº de serie** → no lo cruza ninguna regla (el único cruce de identificador que
  existe es por IMEI, y acá no aplica), pero **sí lo lee el LLM**. Sirve para probar si el modelo
  detecta la contradicción, no para disparar una regla.

> Ojo con una expectativa que suena razonable y es falsa: el factor `purchase_to_report_time` **no
> usa la fecha de esta factura**. Usa `policy.effectiveFrom` como proxy de la fecha de compra
> ([evaluador](../../classification-service/src/main/java/ar/edu/utn/frba/arbiter/classification/services/risk/evaluators/PurchaseToReportTimeEvaluator.java#L30-L43)).
> Mover la fecha de la factura no mueve ese factor.

### `imei_deregistration` — Constancia de bloqueo del equipo · `bloqueo_equipo_tecnologia.pdf`

El equivalente para una notebook de la baja de IMEI. Un equipo portátil no se da de baja de una red
móvil —no está en ninguna—: se **bloquea contra la cuenta del fabricante** y su número de serie
queda registrado como sustraído.

Aporta lo mismo que la baja de IMEI en Celulares: **el bien quedó inutilizable**, lo que sostiene que
el reclamo es por pérdida total y no por un equipo que sigue en uso. Además cierra la coherencia del
relato: dice a qué hora se pidió el bloqueo, a qué hora lo recibió el equipo, y cita el número de
actuación policial.

El documento **declara explícitamente** que el equipo no tiene IMEI ("no aplica — el equipo no posee
módem de telefonía móvil") y que se identifica por nº de serie. Es deliberado: así el papel es
coherente con el slot en el que viaja, sin inventar un dato que no existe.

Qué se puede mutar:

- **Sacarlo** → `FALTA_DOCUMENTACION` (está en la agenda del ramo).
- **Fechar el bloqueo antes del hecho** → `checkDocumentDate` lo marca (tolerancia: una semana).
- Contradecir la hora del bloqueo contra la del acta → no hay regla que lo cruce; queda para el LLM.

### `last_connection` — Informe de última conexión · `ultima_conexion_tecnologia.pdf`

**Corrobora hora y lugar del hecho con un dato técnico**, independiente del relato: el equipo estaba
en la red del campus hasta minutos antes, se conectó una última vez a una red Wi-Fi abierta a pocas
cuadras del lugar del robo, recibió ahí el comando de bloqueo, y no volvió a aparecer.

Es el documento que hace verificable la línea de tiempo. Sin él, la hora del hecho es solo lo que
declaró la asegurada.

Qué se puede mutar:

- **Sacarlo** → `FALTA_DOCUMENTACION`.
- **Poner registros posteriores al bloqueo**, o una ubicación lejos del lugar denunciado → no hay
  regla automática que lo cruce; es material para que el LLM levante la contradicción. Buen caso para
  `LLM_SOLICITA_REVISION_MANUAL`.

## 4 · Qué documentos lee el sistema en cada camino

Esto no es obvio y cambia lo que se puede afirmar de una corrida
([ClassificationOrchestrator.java:203-246](../../classification-service/src/main/java/ar/edu/utn/frba/arbiter/classification/services/ClassificationOrchestrator.java#L203-L246)):

| Momento | Qué documentos se leen |
|---|---|
| Gate de la agenda | **ninguno** — solo verifica que los cuatro *tipos* estén adjuntos. Si falta uno, corta con `FALTA_DOCUMENTACION` y no analiza nada |
| Gate de Fast Track | **solo la denuncia policial** (los que pide `requiredDocumentTypes`) |
| Si dio Fast Track | ahí termina: los otros tres **nunca se OCRean** |
| Si NO dio Fast Track | se extraen **los cuatro** y su texto entra al prompt del LLM |

O sea: en una corrida que da `FAST_TRACK`, tres de los cuatro PDFs solo se usaron para completar la
agenda. Para ejercitar la lectura del modelo hay que armar un caso que **no** pase el gate.

Que sean de **una sola página** importa por lo mismo: `OllamaDocumentAnalyzer` rasteriza el PDF a
150 DPI y manda **cada página** al modelo de visión. Un PDF de 5 páginas son 5 inferencias.

## 5 · Los datos del caso

| Campo | Valor |
|---|---|
| Póliza | `POL-TEC-2026-311` — Seguro de Tecnología Portátil |
| Asegurada | Martina Soteras — DNI 42.987.654 |
| Bien | Apple MacBook Air 15" (M3, 2024), 16 GB / 512 GB, nº de serie `H7QWK3F9LM` |
| Ramo / hecho generador | Tecnología Portátil (2) / Robo en vía pública (7) |
| Monto reclamado | $1.980.000 (factura: $2.100.000) |
| Fecha del hecho | **ayer, 22:10** (relativa — ver abajo) |
| Denuncia policial | +1 h 25 min → holgadísimo contra las 72 hs de D12 |

**El set caduca a las 96 hs y hay que regenerarlo.** `cases.reported_at` es `@CreationTimestamp` y la
regla D11 compara `reportedAt − eventDate` contra el plazo de la cobertura. Por eso el generador
ancla el hecho a *ayer a las 22:10*: fecha relativa, hora fija. El script imprime la fecha exacta de
vencimiento al terminar.

La cadena temporal que comparten los cuatro documentos:

```
22:10  robo en Av. Medrano y Av. Corrientes
22:28  la asegurada activa el bloqueo remoto desde el celular
22:31  el equipo se conecta a una red abierta, recibe el bloqueo y desaparece
23:35  denuncia policial en la Comisaría Vecinal 5-A
11:00  (día siguiente) el service emite las dos constancias
```

## 6 · Cómo correrlo

```bash
node docs/postman/test-docs/generar-fixtures-tecnologia.js

TEC=docs/postman/test-docs/fraude/tec-portatil

curl -X POST http://localhost:8083/api/v1/cases \
  -H "Authorization: Bearer $TOKEN" \
  -F "case=<$TEC/caso_tecnologia.json;type=application/json" \
  -F "police_report=@$TEC/denuncia_policial_tecnologia.pdf;type=application/pdf" \
  -F "purchase_proof=@$TEC/factura_compra_tecnologia.pdf;type=application/pdf" \
  -F "imei_deregistration=@$TEC/bloqueo_equipo_tecnologia.pdf;type=application/pdf" \
  -F "last_connection=@$TEC/ultima_conexion_tecnologia.pdf;type=application/pdf"
```

El `case` va **desde archivo** (`=<`, no `=@`): con `@` curl lo manda como parte de archivo y Spring
lo bindea al `Map<String, MultipartFile> documents` en vez de al `@RequestPart("case")`. Y desde
archivo y no inline, porque los acentos escritos en la línea de comandos se mandan en la
codificación de la consola y el backend responde `400 — Invalid UTF-8 middle byte`.

## 7 · Estado de la verificación

**Los cuatro PDFs están verificados; la corrida de punta a punta todavía no.**

Verificado: los cuatro cargan en **PDFBox 3.0.3** —la misma librería que usa
`OllamaDocumentAnalyzer`—, son de una sola página, su capa de texto se extrae completa y con
acentos, y rasterizan a 150 DPI (1240×1753). Coherencia cruzada comprobada por extracción de texto:
el nº de serie y el DNI aparecen en los cuatro, la MAC y el nº de constancia de bloqueo en los dos
del service, el nº de actuación policial en los dos que corresponde.

**Dos cosas del entorno frenan la corrida, y ninguna es de los documentos:**

1. **El asegurado de prueba no llega a esa póliza.** `POL-TEC-2026-311` vive en `arbiter_provincia`,
   y `PolicyTenantLocator` busca solo entre las aseguradoras del token, que salen de `user_insurer` —
   el usuario 1 tiene únicamente BBVA, así que el alta responde 422. El seed ya modela a Martina como
   asegurada de Provincia (`arbiter_provincia.insured(1).user_id = 1`); falta la fila
   `user_insurer (1, 2)`.
2. **La clasificación se cae después del alta.** El `MockInsurerAdapter` de classification no tiene
   ninguna póliza del ramo y `getPolicy` tira excepción ante un número desconocido; con el perfil
   `insurer-db` reaparece el defecto ya conocido (consulta `aseguradora.poliza`, que no existe en el
   esquema por tenant — ver §6 del [caso de Celulares](caso-prueba-fast-track-celulares.md)).
