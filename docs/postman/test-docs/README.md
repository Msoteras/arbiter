# Fixtures de prueba — cómo generarlos y cómo sumar los tuyos

Documentos simulados (PDF) + el payload JSON para dar de alta expedientes de prueba contra
`cases-service`. **No son archivos que se editan a mano: se generan con un script**, y hay una buena
razón para eso — se vencen (ver §2).

## 1 · Qué hay acá

```
docs/postman/test-docs/
├── lib-pdf.js                      motor PDF + helpers de fecha (compartido)
├── perfiles.js                     quién firma cada variante (ver más abajo)
├── generar-fixtures.js             escenario Celulares · Fast Track
├── generar-fixtures-tecnologia.js  escenario Tecnología Portátil · Robo
├── foto_equipo_para_fraude.jpg     foto real de un A56 (dispara la cascada forense)
├── denuncia_policial_ambigua.pdf   fixture viejo, suelto
├── fraude/
│   ├── fast-track/                 ← 4 PDFs + caso_fast_track.json
│   └── tec-portatil/               ← 4 PDFs + caso_tecnologia.json
└── veridica/
    ├── fast-track/                 ← los mismos 4, otra variante
    └── tec-portatil/
```

Los `.js` son **la fuente**; lo que está dentro de `fraude/` y `veridica/` es **la salida**, y se
pisa cada vez que corrés el generador. Si querés cambiar un dato de un caso, se toca el script, nunca
el PDF.

Cada escenario tiene su documento explicando qué prueba y qué significa cada documento:

- [caso-prueba-fast-track-celulares.md](../../siniestros/caso-prueba-fast-track-celulares.md)
- [caso-prueba-tecnologia-portatil.md](../../siniestros/caso-prueba-tecnologia-portatil.md)

### Las dos variantes

Son **el mismo caso, el mismo layout y las mismas fechas**: lo único que cambia es quién firma y si
la página lleva la leyenda de simulado.

| | `fraude/` | `veridica/` |
|---|---|---|
| Firmante | Martina Soteras — DNI 42.987.654 | Roman Castillo — DNI 33.845.219 |
| Leyenda "documento simulado" al pie | sí | **no** |
| Para qué | el juego de siempre, autoexplicativo | probar el pipeline sin que el modelo de visión lea un cartel que le anticipa que el papel es de prueba |

Los dos perfiles viven en [`perfiles.js`](perfiles.js), que también resuelve la concordancia de
género del relato: un acta que dice "la denunciante" sobre un hombre es el tipo de detalle que
delata un documento armado a las apuntadas.

> **Los de `veridica/` no se distinguen a simple vista de un documento real.** Las empresas y los
> números siguen siendo ficticios y el archivo se sigue identificando como fixture en los metadatos
> del PDF (`/Keywords`, `/Producer`, `/Subject`) — que no se ven en la página ni entran al OCR, así
> que no ensucian la prueba. Pero sin la leyenda al pie, impresos o reenviados fuera del repo, no hay
> nada que le avise a una persona que son de prueba. Que no salgan de acá.

## 2 · Generar (o renovar) un set

Desde la raíz del repo, sin argumentos: cada script escribe en la carpeta de su escenario.

```bash
node docs/postman/test-docs/generar-fixtures.js             # → fraude/fast-track/
node docs/postman/test-docs/generar-fixtures-tecnologia.js  # → fraude/tec-portatil/
```

Con `--veridica` los mismos dos scripts escriben la otra variante:

```bash
node docs/postman/test-docs/generar-fixtures.js --veridica             # → veridica/fast-track/
node docs/postman/test-docs/generar-fixtures-tecnologia.js --veridica  # → veridica/tec-portatil/
```

No hay un script por variante a propósito: el layout de los documentos es el mismo, y duplicarlo
significaría que arreglar una redacción hay que hacerlo en cuatro archivos y que el día que alguien
se olvide de uno, las variantes divergen en silencio.

Se le puede pasar otro destino (`node ... /tmp/prueba`) para generar aparte y comparar antes de pisar
lo que hay.

**Los sets caducan y hay que regenerarlos.** `cases.reported_at` es `@CreationTimestamp` —el momento
en que se crea el expediente— y la regla D11 compara `reportedAt − eventDate` contra el plazo de
denuncia de la cobertura. Con una fecha de hecho fija, el fixture deja de comportarse como dice su
documentación **sin que nada avise**: el caso simplemente pierde el Fast Track.

Por eso el hecho se ancla a *ayer* a una hora fija, y por eso hay que volver a correr el script:

| Escenario | Plazo de la cobertura | Dura |
|---|---|---|
| Celulares · Fast Track | 72 hs | 3 días desde que lo generaste |
| Tecnología Portátil | 96 hs | 4 días |

Cada script imprime al terminar la fecha exacta de vencimiento. Si un caso que "andaba" empieza a dar
otra clasificación, **lo primero a descartar es que el set esté vencido.**

## 3 · Usarlo

El `case` va **desde archivo** (`=<`, no `=@`): con `@` curl lo manda como parte de archivo y Spring
lo bindea al `Map<String, MultipartFile> documents` en vez de al `@RequestPart("case")`. Y desde
archivo y no inline, porque los acentos escritos en la consola llegan en otra codificación y el
backend responde `400 — Invalid UTF-8 middle byte`.

```bash
FT=docs/postman/test-docs/fraude/fast-track

curl -X POST http://localhost:8083/api/v1/cases \
  -H "Authorization: Bearer $TOKEN" \
  -F "case=<$FT/caso_fast_track.json;type=application/json" \
  -F "police_report=@$FT/denuncia_policial_fast_track.pdf;type=application/pdf" \
  -F "purchase_proof=@$FT/factura_compra_fast_track.pdf;type=application/pdf" \
  -F "imei_deregistration=@$FT/baja_imei_fast_track.pdf;type=application/pdf" \
  -F "last_connection=@$FT/ultima_conexion_fast_track.pdf;type=application/pdf"
```

(El `$TOKEN` sale del login; el comando completo está en el documento de cada caso.)

Desde Postman es lo mismo: `form-data`, una fila por documento, y **el nombre de la fila es el tipo
de documento** (`police_report`, `purchase_proof`, `imei_deregistration`, `last_connection`,
`item_photo`), no el nombre del archivo.

> **El backend no lee el nombre del archivo.** Lo único que mira es el nombre de la parte multipart.
> Por eso `bloqueo_equipo_tecnologia.pdf` viaja como `imei_deregistration` aunque una notebook no
> tenga IMEI: el slot de la agenda documental se llama así.

## 4 · Sumar un escenario nuevo

Esta es la parte reutilizable. `lib-pdf.js` ya resuelve todo lo aburrido —escribir el PDF a mano,
codificar los acentos, alinear importes, el pie de "documento simulado"—, así que un escenario nuevo
es un archivo con los datos del caso y cuatro funciones de layout.

**1 · Copiá el generador más parecido** y renombralo (`generar-fixtures-<escenario>.js`).

**2 · Cambiá el bloque `CASE`.** Es la única fuente de verdad del escenario: nombre, documento,
equipo, fechas, importes, empresas. Todo lo que sale en los PDFs y en el JSON del payload sale de
ahí, así que **los documentos no pueden contradecirse entre sí** aunque los edites después.

**3 · Anclá las fechas a la corrida, no al calendario.** Copiá el patrón:

```js
const NOW = new Date();
const EVENT_AT = new Date(NOW);
EVENT_AT.setDate(EVENT_AT.getDate() - 1);   // ayer
EVENT_AT.setHours(22, 10, 0, 0);            // a una hora fija que le quede bien al relato
```

Y derivá el resto con `plus(EVENT_AT, minutos, segundos)`. Un desplazamiento puro ("hace N horas")
deja el hecho a cualquier hora: a las 3 de la mañana el relato deja de cerrar.

**4 · Elegí una hora distinta de la de los otros escenarios.** Dos casos que ocurren a la misma hora
se confunden al leer los logs.

**5 · Apuntá la salida a tu carpeta**: `path.join(__dirname, 'fraude', '<tu-escenario>')`.

**6 · Documentá el caso** en `docs/siniestros/caso-prueba-<escenario>.md`: qué clasificación espera,
por qué, y qué aporta cada documento.

Las dos variantes te salen gratis: si tomás el firmante de `PROFILE.insured`, envolvés el `footer(p)`
en `if (PROFILE.disclaimer)` y usás las piezas de `G` para la concordancia, tu escenario responde a
`--veridica` sin una línea más.

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
| `footer(p)` | el pie de "documento simulado" — **obligatorio en todos** |
| `build(page, meta)` | arma el PDF final (`title`, `author`, `subject`, `created`) |
| `d` · `hm` · `hms` · `iso` · `pdfDate` · `plus` | formateo de fechas |
| `cuit(prefijo, cuerpo)` | CUIT con dígito verificador válido |

### Reglas de la casa

- **Una sola página por PDF.** `OllamaDocumentAnalyzer` rasteriza a 150 DPI y manda **cada página**
  al modelo de visión: un PDF de 5 páginas son 5 inferencias.
- **Texto seleccionable, no imagen.** El gate de Fast Track exige que el documento requerido tenga
  texto extraído **no vacío**; si el OCR devuelve nada, el Fast Track se cae aunque el archivo esté.
- **Empresas y personas ficticias**, siempre. No queremos comprobantes que aparenten ser de una
  empresa real. El pie de página lo deja escrito.
- **Solo WinAnsi.** Si metés un carácter fuera de esa codificación el script falla al generar (mejor
  ahí que en un PDF ilegible). Nada de emojis ni de comillas tipográficas raras.
- **Los importes que aparezcan en un documento tienen que estar dentro del 10% del `claimedAmount`**,
  o `DocumentInconsistencyEvaluator.checkAmount` los va a marcar como contradicción. Salvo, claro,
  que ese sea justamente el caso que querés probar.

## 5 · Verificar lo que generaste

El script ya valida la codificación al escribir. Para comprobar que el PDF es procesable por el
pipeline real, cargalo con **PDFBox 3.0.3** —la misma librería que usa `OllamaDocumentAnalyzer`— y
mirá tres cosas: que sea de 1 página, que el texto se extraiga con acentos, y que rasterice a 150 DPI
(tiene que dar 1240×1753).

Y una comprobación que no da ninguna librería: **abrí los cuatro y leelos**. La coherencia entre
documentos —que el nº de serie, el DNI y las fechas sean los mismos en todos— la garantiza el objeto
`CASE`; que la historia cierre —que el bloqueo sea posterior al robo, que la constancia cite la
actuación correcta— lo garantiza el que lo escribió.

## 6 · Qué se sube al repo

**Todo: los cuatro `.js`, los JSON y los PDFs.** Los `.js` no son opcionales — `generar-fixtures.js`
hace `require('./lib-pdf')` y `require('./perfiles')`, así que sueltos no arrancan. Y sin los
generadores, cuando el set se vence (3 o 4 días) nadie lo puede renovar: quedan PDFs que ya no
prueban lo que dicen probar.
