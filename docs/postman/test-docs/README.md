# Fixtures de prueba — cómo generarlos y cómo sumar los tuyos

Documentos simulados (PDF) + el payload JSON para dar de alta expedientes de prueba contra
`cases-service`. **No son archivos que se editan a mano: se generan con un script**, y hay una buena
razón para eso — se vencen (ver §3).

## 1 · Qué hay acá

```
docs/postman/test-docs/
├── lib-pdf.js                      motor PDF + helpers de fecha (compartido)
├── perfiles.js                     quién firma cada variante (ver §2)
├── generar-fixtures.js             ramo Celulares — 4 hechos generadores
├── generar-fixtures-tecnologia.js  ramo Tecnología Portátil — 3 hechos generadores
├── mutaciones-celulares.js         el robo de Celulares con UNA cosa cambiada (ver §9)
├── historial-celulares.js          tres casos de Camila en orden, para ver pesar el historial (ver §10)
├── generar-todo.js                 regenera todos los sets de una
├── foto_equipo_para_fraude.jpg     foto de un A56 → item_photo de Celulares
├── foto_notebook_para_fraude.jpg   foto de un MacBook Air → item_photo de Tecnología Portátil
├── denuncia_policial_ambigua.pdf   fixture viejo, suelto
├── conMarcaDePrueba/
│   ├── celulares/{robo, hurto, caida, rotura accidental}/
│   └── tec-portatil/{robo, hurto, danio_acc}/
├── sinMarca/
│   └── (la misma estructura, otra variante)
├── camila/celulares/{robo, hurto}/   set limpio de Camila Ferreyra (ver §10)
│   └── historial/{1-primer-robo, 2-segundo-robo, 3-hurto}/
└── mutaciones/celulares/<mutación>/  PDFs + payload + esperado.json (ver §9)
```

Los `.js` son **la fuente**; lo que cuelga de `conMarcaDePrueba/` y `sinMarca/` es **la salida**, y se
pisa cada vez que corrés el generador. Si querés cambiar un dato de un caso, se toca el script, nunca
el PDF.

Cada carpeta de hecho generador es **autocontenida**: trae sus PDFs y su `caso_<hecho>.json`, listo
para mandar de una.

## 2 · Las dos variantes

Son **los mismos casos, el mismo layout y las mismas fechas**: lo único que cambia es quién firma y
si la página lleva la leyenda de simulado.

| | `conMarcaDePrueba/` | `sinMarca/` |
|---|---|---|
| Firmante | Martina Soteras — DNI 42.987.654 | Roman Castillo — DNI 33.845.219 |
| Leyenda "documento simulado" al pie | sí | **no** |
| Para qué | el juego de siempre, autoexplicativo | probar el pipeline sin que el modelo de visión lea un cartel que le anticipa que el papel es de prueba |

Los dos perfiles viven en [`perfiles.js`](perfiles.js), que también resuelve la concordancia de
género del relato: un acta que dice "la denunciante" sobre un hombre es el tipo de detalle que
delata un documento armado a las apuradas.

> **Los de `sinMarca/` no se distinguen a simple vista de un documento real.** Las empresas y los
> números siguen siendo ficticios y el archivo se sigue identificando como fixture en los metadatos
> del PDF (`/Keywords`, `/Producer`, `/Subject`) — que no se ven en la página ni entran al OCR, así
> que no ensucian la prueba. Pero sin la leyenda al pie, impresos o reenviados fuera del repo, no hay
> nada que le avise a una persona que son de prueba. Que no salgan de acá.

## 3 · Generar (o renovar) los sets

**Todo de una**, que es lo que conviene para renovarlos:

```bash
node docs/postman/test-docs/generar-todo.js     # imprime cuándo vence cada set
```

O de a uno:

Desde la raíz del repo. Cada script escribe **todos los hechos generadores de su ramo**:

```bash
node docs/postman/test-docs/generar-fixtures.js              # → conMarcaDePrueba/celulares/*
node docs/postman/test-docs/generar-fixtures-tecnologia.js   # → conMarcaDePrueba/tec-portatil/*
```

Con `--sin-marca`, los mismos dos scripts escriben la otra variante:

```bash
node docs/postman/test-docs/generar-fixtures.js --sin-marca
node docs/postman/test-docs/generar-fixtures-tecnologia.js --sin-marca
```

No hay un script por variante ni por hecho generador a propósito: el layout de los documentos es el
mismo y duplicarlo significaría que arreglar una redacción hay que hacerlo en ocho archivos, y que el
día que alguien se olvide de uno las variantes divergen en silencio.

Se le puede pasar otro destino (`node ... /tmp/prueba`) para generar aparte y comparar antes de pisar
lo que hay.

**Los sets caducan y hay que regenerarlos.** `cases.reported_at` es `@CreationTimestamp` —el momento
en que se crea el expediente— y la regla D11 compara `reportedAt − eventDate` contra el plazo de
denuncia de la cobertura. Con una fecha de hecho fija, el fixture deja de comportarse como dice su
documentación **sin que nada avise**: el caso simplemente pierde el Fast Track.

Por eso cada hecho se ancla a *ayer* a una hora fija propia, y por eso hay que volver a correr el
script: Celulares dura **72 hs** y Tecnología Portátil **96 hs** (el plazo de cada cobertura). Cada
script imprime la fecha exacta de vencimiento al terminar. Si un caso que "andaba" empieza a dar otra
clasificación, **lo primero a descartar es que el set esté vencido.**

## 4 · Qué caso tiene qué, y qué falta

La agenda documental se configura **por ramo + hecho generador**
([seed](../../../db/init-multitenant.sql#L1020-L1050)), así que cada caso pide su propia lista. Un
set que no cubre exactamente los tipos exigidos por *su* hecho generador corta en
`FALTA_DOCUMENTACION` y no llega a evaluarse. Adjuntar de más no molesta; que falte uno, sí.

### Celulares — póliza `POL-CEL-2026-042`, Samsung Galaxy A56 5G

| Carpeta | Hecho generador | La agenda pide | Estado |
|---|---|---|---|
| `robo/` | Robo en vía pública | `police_report` · `purchase_proof` · `imei_deregistration` · `last_connection` | ✅ completo |
| `hurto/` | Hurto | los mismos cuatro | ✅ completo |
| `caida/` | Caída | `purchase_proof` · `repair_quote` · `item_photo` | ⚠️ falta adjuntar la foto (ver abajo) |
| `rotura accidental/` | Rotura accidental | `purchase_proof` · `repair_quote` · `item_photo` | ⚠️ ídem |

Para los dos casos de daño, el `item_photo` es **`foto_equipo_para_fraude.jpg`**, que está en la raíz
de esta carpeta: el mismo A56 de la póliza. Ver "Las dos fotos", más abajo.

### Tecnología Portátil — póliza `POL-TEC-2026-311`, MacBook Air 15" M3

| Carpeta | Hecho generador | La agenda pide | Estado |
|---|---|---|---|
| `robo/` | Robo en vía pública | `police_report` · `purchase_proof` · `item_photo` | ✅ completo |
| `hurto/` | Hurto | los mismos tres | ✅ completo |
| `danio_acc/` | Daño accidental | `purchase_proof` · `repair_quote` · `item_photo` | ✅ completo |

El `item_photo` de estos tres es **`foto_notebook_para_fraude.jpg`**, también en la raíz: un MacBook
Air Medianoche cerrado sobre una mesa, el mismo color que declara la póliza.

Los casos de robo y hurto de Tecnología traen además la constancia de bloqueo remoto y —en robo— el
informe de última conexión. **La agenda ya no los exige**: quedan como adjuntos extra, que el LLM lee
cuando el caso no fast-trackea.

### Las dos fotos

Ninguna sale del generador —son imágenes, no PDFs— y ninguna se copia dentro de los casos: se
adjuntan desde la raíz para no repetir el mismo archivo en seis carpetas.

| Archivo | Qué muestra | Origen |
|---|---|---|
| `foto_equipo_para_fraude.jpg` | Samsung Galaxy A56 5G, el mismo modelo de `POL-CEL-2026-042` | Wikimedia Commons, [`File:SmsnGlxA565gBack2026040500.jpg`](https://commons.wikimedia.org/wiki/File:SmsnGlxA565gBack2026040500.jpg) — OnionBulb, CC BY-SA 4.0 |
| `foto_notebook_para_fraude.jpg` | MacBook Air Medianoche cerrado sobre un escritorio | Wikimedia Commons, [`File:M2 Macbook Air Midnight model - 2.jpg`](https://commons.wikimedia.org/wiki/File:M2_Macbook_Air_Midnight_model_-_2.jpg) — KKPCW (Kyu3), CC BY-SA 4.0, redimensionada a 1620 px |

Dos cosas para tener presentes al leer un resultado con foto:

- **Las dos vienen de la web**, así que Google Vision las va a encontrar publicadas y el factor
  `image_web_match` va a puntuar alto. El expediente clasifica igual; lo que se infla es el
  `riskScore`. Si lo que estás probando no es la cascada forense, conviene correr el caso sin foto y
  aceptar el `FALTA_DOCUMENTACION`, o mirar el veredicto ignorando ese factor.
- **Muestran el equipo sano.** Para robo y hurto está bien —es la foto de "así era mi equipo"—, pero
  en los casos de daño (caída, rotura, daño accidental) lo coherente sería una foto del daño. Si
  alguien saca una foto propia de una pantalla rota, entra ahí y además resuelve el punto anterior.
- La del MacBook es un **M2 de 13"** y la póliza declara un **M3 de 15"**: con la tapa cerrada la
  diferencia no se ve, pero es un detalle a tener en cuenta si el modelo llega a comentar la foto.

> **Un hallazgo esperable en los casos con `repair_quote`:** `DocumentInconsistencyEvaluator.checkAmount`
> compara el importe de **cada** documento contra el monto reclamado con un 10% de tolerancia. En un
> reclamo por daño, el monto reclamado es el costo de la reparación, pero la factura de compra dice el
> valor del equipo — así que la factura va a levantar un hallazgo de `document_inconsistency`. No es un
> error del fixture: es el evaluador comparando cosas que no son comparables.

## 5 · Usarlo

El `case` va **desde archivo** (`=<`, no `=@`): con `@` curl lo manda como parte de archivo y Spring
lo bindea al `Map<String, MultipartFile> documents` en vez de al `@RequestPart("case")`. Y desde
archivo y no inline, porque los acentos escritos en la consola llegan en otra codificación y el
backend responde `400 — Invalid UTF-8 middle byte`.

```bash
C=docs/postman/test-docs/conMarcaDePrueba/celulares/robo

curl -X POST http://localhost:8083/api/v1/cases \
  -H "Authorization: Bearer $TOKEN" \
  -F "case=<$C/caso_robo.json;type=application/json" \
  -F "police_report=@$C/denuncia_policial_celulares.pdf;type=application/pdf" \
  -F "purchase_proof=@$C/factura_compra_celulares.pdf;type=application/pdf" \
  -F "imei_deregistration=@$C/baja_imei_celulares.pdf;type=application/pdf" \
  -F "last_connection=@$C/ultima_conexion_celulares.pdf;type=application/pdf"
```

Un caso de daño lleva la foto de la raíz:

```bash
C="docs/postman/test-docs/conMarcaDePrueba/celulares/caida"
F=docs/postman/test-docs/foto_equipo_para_fraude.jpg

curl -X POST http://localhost:8083/api/v1/cases \
  -H "Authorization: Bearer $TOKEN" \
  -F "case=<$C/caso_caida.json;type=application/json" \
  -F "purchase_proof=@$C/factura_compra_celulares.pdf;type=application/pdf" \
  -F "repair_quote=@$C/presupuesto_reparacion_celulares.pdf;type=application/pdf" \
  -F "item_photo=@$F;type=image/jpeg"
```

(La carpeta `rotura accidental` lleva espacio: entrecomillá la variable.)

Desde Postman es lo mismo: `form-data`, una fila por documento, y **el nombre de la fila es el tipo
de documento** (`police_report`, `purchase_proof`, `imei_deregistration`, `last_connection`,
`repair_quote`, `item_photo`), no el nombre del archivo.

> **El backend no lee el nombre del archivo.** Lo único que mira es el nombre de la parte multipart.
> Por eso `bloqueo_equipo_tecnologia.pdf` viaja como `imei_deregistration` aunque una notebook no
> tenga IMEI: el slot de la agenda documental se llama así.

## 6 · Sumar un escenario nuevo

`lib-pdf.js` ya resuelve todo lo aburrido —escribir el PDF a mano, codificar los acentos, alinear
importes, el pie de "documento simulado"—, y los generadores ya están armados como una **lista de
escenarios**: sumar un hecho generador es sumar un objeto a esa lista, no escribir un archivo nuevo.

**1 · Agregá el escenario** al array `SCENARIOS` del generador de su ramo. Cada objeto declara su
carpeta, su `claimCause`, la hora del hecho, el monto, la descripción del payload y **qué documentos
lleva** (`documents: ['purchase_proof', 'repair_quote']`). El resto lo arma el bucle del final.

**2 · Mirá la agenda de tu hecho generador antes de elegir los documentos** (§4). La lista de
`documents` tiene que cubrir lo que el seed exige para ese `claim_cause_id`.

**3 · Anclá las fechas a la corrida, no al calendario.** Usá `ayerA(hora, minuto)` y derivá el resto
con `plus(evento, minutos, segundos)`. Un desplazamiento puro ("hace N horas") deja el hecho a
cualquier hora: a las 3 de la mañana el relato deja de cerrar.

**4 · Elegí una hora distinta de la de los otros escenarios.** Dos casos a la misma hora se confunden
al leer los logs.

**5 · Si necesitás un tipo de documento que no existe todavía**, escribí su función de layout y
sumala a `BUILDERS` con el nombre de archivo. Las que ya están: acta de denuncia, factura de compra,
baja de IMEI / bloqueo de equipo, última conexión y presupuesto de reparación.

**6 · Documentá el caso** en `docs/siniestros/`: qué clasificación espera, por qué, y qué aporta cada
documento.

Las dos variantes te salen gratis: si tomás el firmante de `INSURED`, envolvés el `footer(p)` en
`if (PROFILE.disclaimer)` y usás las piezas de `G` para la concordancia, tu escenario responde a
`--sin-marca` sin una línea más.

### Lo que te da `lib-pdf.js`

| Pieza | Para qué |
|---|---|
| `new Page()` | una página A4 con cursor vertical propio |
| `.text(str, opts)` | línea de texto — `font` (`F1` normal / `F2` negrita), `size`, `center`, `x`, `leading` |
| `.field(label, valor)` | etiqueta en negrita + valor alineado en columna |
| `.moneyRow(label, importe)` | etiqueta a la izquierda, importe a la derecha, misma línea |
| `.section(titulo)` | encabezado de sección |
| `.rule()` · `.gap(n)` · `.box(alto)` | línea horizontal, espacio, recuadro |
| `letterhead(p, org, dir, cuit)` | membrete centrado |
| `footer(p)` | el pie de "documento simulado" — siempre detrás de `if (PROFILE.disclaimer)` |
| `build(page, meta)` | arma el PDF final (`title`, `author`, `subject`, `created`) |
| `d` · `hm` · `hms` · `iso` · `pdfDate` · `plus` | formateo de fechas |
| `cuit(prefijo, cuerpo)` | CUIT con dígito verificador válido |

### Reglas de la casa

- **Una sola página por PDF.** `OllamaDocumentAnalyzer` rasteriza a 150 DPI y manda **cada página**
  al modelo de visión: un PDF de 5 páginas son 5 inferencias.
- **Texto seleccionable, no imagen.** El gate de Fast Track exige que el documento requerido tenga
  texto extraído **no vacío**; si el OCR devuelve nada, el Fast Track se cae aunque el archivo esté.
- **Empresas y personas ficticias**, siempre. No queremos comprobantes que aparenten ser de una
  empresa real. El pie de página lo deja escrito en la variante con marca.
- **Solo WinAnsi.** Si metés un carácter fuera de esa codificación el script falla al generar (mejor
  ahí que en un PDF ilegible). Nada de emojis ni de comillas tipográficas raras.
- **Un hecho generador, un relato.** Un hurto no es un robo con otro título: si el acta describe un
  tirón o un forcejeo, la carátula tiene que ser robo. Es lo que hace que el caso sirva para probar
  algo y no solo para llenar slots.

## 7 · Verificar lo que generaste

El script ya valida la codificación al escribir. Para comprobar que el PDF es procesable por el
pipeline real, cargalo con **PDFBox 3.0.3** —la misma librería que usa `OllamaDocumentAnalyzer`— y
mirá tres cosas: que sea de 1 página, que el texto se extraiga con acentos, y que rasterice a 150 DPI
(tiene que dar 1240×1753).

Y una comprobación que no da ninguna librería: **abrí los documentos de un caso y leelos juntos**. La
coherencia de los datos —que el IMEI, el DNI y las fechas sean los mismos en todos— la garantiza el
objeto del escenario; que la historia cierre —que el bloqueo sea posterior al hecho, que la constancia
cite la actuación correcta, que el diagnóstico del service se corresponda con el daño denunciado— lo
garantiza el que lo escribió.

## 8 · Qué se sube al repo

**Todo: los cuatro `.js`, los JSON y los PDFs.** Los `.js` no son opcionales — los generadores hacen
`require('./lib-pdf')` y `require('./perfiles')`, así que sueltos no arrancan. Y sin los generadores,
cuando el set se vence (3 o 4 días) nadie lo puede renovar: quedan PDFs que ya no prueban lo que
dicen probar.

## 9 · Mutaciones — casos que el pipeline tiene que rechazar

Los escenarios de §4 son todos coherentes: sirven para probar que un caso bueno pasa, no que uno malo
se detecta. `mutaciones-celulares.js` toma el **robo de Celulares** y le cambia **una sola cosa** por
caso, así que si el pipeline reacciona (o no) hay una única variable a la que atribuírselo.

Las firma un asegurado propio, **Valentín Aguirre** (DNI 38.614.270, póliza `POL-CEL-2026-777`,
perfil `mutaciones` en `perfiles.js`, sin leyenda de simulado), y se generan aparte:

```bash
node docs/postman/test-docs/generar-fixtures.js --mutaciones   # → mutaciones/celulares/*
```

| Carpeta | Qué cambia | Documento | Quién lo tendría que detectar | Resultado hoy |
|---|---|---|---|---|
| `control` | nada | — | — | `FAST_TRACK` |
| `imei-distinto` | IMEI que no es el de la póliza | factura | `checkImei` + LLM | `FAST_TRACK` |
| `marca-distinta` | factura de un Motorola, póliza de un Samsung | factura | `checkBrandAndModel` + LLM | `FAST_TRACK` |
| `importe-distinto` | factura de $ 389.999 contra $ 620.000 reclamados | factura | `checkAmount` + LLM | `FAST_TRACK` |
| `fecha-acta-distinta` | acta del día siguiente; el asegurado declara esa misma noche | acta | `checkDeclaredPoliceReportDate` (D12) | `FAST_TRACK` |
| `constancia-anterior-al-hecho` | bloqueo de IMEI 12 días antes del robo | baja de IMEI | `checkDocumentDate` + LLM | `FAST_TRACK` |
| `bien-de-familiar` | el equipo lo tenía y lo usaba el cónyuge | acta | `affected_party = FAMILIAR` → `CoverageScopeEvaluator` | sale de Fast Track → LLM |
| `relato-hurto` | declara robo; relato y acta cuentan un descuido en un café | acta + relato | consistencia del relato (LLM) → exclusión de Hurto | `FAST_TRACK` |
| `instrucciones-en-factura` | la factura le ordena al modelo que apruebe | factura | extracción (`visualFindings`) + LLM | `FAST_TRACK` |

**Que siete de nueve den `FAST_TRACK` es el resultado, no un error del set.** El robo base cumple el
gate, y en ese camino el pipeline solo extrae los documentos que el gate exige (acta y factura), el LLM
no corre, y lo único de un documento que puede bloquear el carril rápido es quién fue el damnificado.
Las inconsistencias de datos, las fechas imposibles, el relato que describe otro hecho generador: nada
de eso se mira antes de decidir el Fast Track. `relato-hurto` es el caso más grave — un hurto
declarado como robo entra al carril rápido de una cobertura que excluye el hurto.

Aun así las mutaciones dejan rastro: la factura y el acta se extraen igual, así que
`document_analysis` muestra el IMEI, la marca, el importe, la fecha y la transcripción que la mutación
cambió. Es lo que hay que mirar para confirmar que la extracción leyó bien lo que el gate no usa.

Cada carpeta trae un **`esperado.json`** con qué cambia, dónde mirar (tabla y columna, o evaluador) y
qué clasificación se espera. Se manda igual que cualquier caso de §5, con el payload de la carpeta y
el token de Valentín.

### Una a la vez, con el historial en cero

Cada caso cargado en Arbiter cuenta como siniestro previo de quien lo cargó
(`withArbiterAntecedents`): mandar las nueve seguidas haría que la novena se evalúe con ocho
siniestros previos, y lo que se vería es el historial y no la mutación. Por eso, **antes de cada
una**:

```bash
psql -f scripts/reset-asegurados-de-prueba.sql   # borra los expedientes de Valentín y de Camila, y de nadie más
```

El script tiene los dos DNI fijos y no acepta otro. Como también limpia a Camila, **no lo corras en
medio de su secuencia de historial** (§10). Borra los expedientes con todo lo que cuelga de ellos
(documentos, análisis, liquidación, clasificación, snapshot de póliza) en una sola transacción.

**Puesta en marcha, una sola vez:** correr `db/migrations/2026-09-22-asegurado-mutaciones.sql` (solo
escribe en la BD de la aseguradora), después el alta masiva desde el panel del referente, y aceptar
la invitación que llega por mail. La cuenta, el asegurado en Arbiter y la póliza local salen de ese
flujo, no de la migración.

Para sumar una: un objeto más en la lista de `buildMutations`. Lo que cambia en **un** documento va
en `overrides[tipo]` (`device`, `purchase`, `block`, `appendLines`); lo que cambia el caso entero
(el relato, el acta) se pisa sobre el escenario.

### Antes de leer un resultado: el estado de la base (22/09/2026)

Los esperados asumen la configuración de la base viva a esa fecha. Tres cosas que la condicionan y que
**no** son del fixture:

- **Quién está limpio (22/09).** Martina lleva 15 casos en BBVA y 12 en Provincia: el criterio de
  Fast Track `maxPriorClaims = 0` y el tope anual (D10) no le pasan nunca. Roman tiene 1 por
  aseguradora, así que ya no pasa el gate de BBVA. **Camila Ferreyra** (DNI 38.412.905, póliza
  `POL-CEL-2026-401`) tiene 0 y login activo, pero su póliza cubre un **Google Pixel 8**, así que los
  PDFs de Celulares, que describen un Samsung A56, no se le pueden adjudicar tal cual. Para las
  mutaciones está Valentín; para detectar que algo más cambió está `control`: correlo en la misma
  sesión y compará contra él.
- **El Fast Track se configura por cobertura.** Robo y hurto piden `police_report` + `purchase_proof`;
  daño accidental, `purchase_proof` + `repair_quote`. Hasta el 22/09 el panel guardaba una sola
  configuración por ramo y la copiaba a todas las coberturas, y el 12/09 eso dejó a robo y hurto de
  BBVA pidiendo presupuesto de reparación: ningún robo podía entrar al carril rápido. Si `control` no
  da `FAST_TRACK`, revisar primero la regla FAST_TRACK de la cobertura 1.
- **`document_inconsistency` no tiene peso** en `factor_weight` de ninguna aseguradora: el
  evaluador corre pero no llega a `risk_breakdown`. Aunque lo tuviera, el score es una señal paralela:
  no bloquea el Fast Track.

## 10 · Camila: set limpio y secuencia de historial

**Camila Ferreyra** (DNI 38.412.905, póliza `POL-CEL-2026-401`, un **Google Pixel 8**) es una
asegurada de BBVA que entró por el alta masiva y tiene login propio. Su póliza cubre robo y hurto,
sin daño, así que su set trae solo esos dos. Sus documentos describen el Pixel, no el Samsung de
los demás perfiles: el equipo sale de la póliza de cada perfil (`perfiles.js`), porque
`DocumentInconsistencyEvaluator` compara la marca y el modelo de cada documento contra el bien
asegurado.

```bash
node docs/postman/test-docs/generar-fixtures.js --camila   # → camila/celulares/*
```

Hay dos maneras de usarla, y la diferencia es cuándo se limpia:

| Qué | Carpeta | Limpieza | Para qué |
|---|---|---|---|
| Set limpio | `robo/`, `hurto/` | antes de cada caso | un caso "como el primero": el robo da `FAST_TRACK`; el hurto no, porque reclama 620.000 sobre los 650.000 de la cobertura de hurto |
| Historial | `historial/1…3` | **una sola vez, antes del paso 1** | ver cómo pesan los siniestros previos |

### La secuencia de historial

Tres casos que se mandan **en orden y sin limpiar entre medio**, así cada uno se evalúa con los
anteriores como siniestros previos. Es la contracara de las mutaciones: allá el historial se deja en
cero para que no tape la señal; acá el historial es la señal.

| Paso | Qué es | Previos | Qué se espera |
|---|---|---|---|
| `1-primer-robo` | robo, ayer 10:05 | 0 | `FAST_TRACK` |
| `2-segundo-robo` | robo, ayer 19:25 | 1 | sale de Fast Track por `maxPriorClaims = 0`; el tope anual de robo (2) todavía pasa; va al LLM |
| `3-hurto` | hurto, ayer 13:40 | 2 | `MAX_EVENTS_YEAR` en FAIL: la cobertura de hurto tiene tope 1 |

El orden que importa es el de **carga**, no el de los hechos: el paso 3 ocurrió antes que el 2, y el
2 cuenta igual porque la ventana del tope anual compara fechas, no horas. Cada paso trae su
`esperado.json` con qué mirar en `rule_result` y `risk_breakdown`.

Los tres hechos son del mismo día a propósito: lo que vence la secuencia es el plazo de denuncia del
primer paso (72 hs), y el generador imprime cuándo.
