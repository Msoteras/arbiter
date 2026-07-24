# Frontend — Sección "Análisis forense" en el panel del analista

**Para:** quien tome el frontend Angular.
**Contexto:** el backend corre un análisis de fraude por imágenes (comparación contra siniestros previos + búsqueda en internet). Este doc describe cómo mostrarlo en la bandeja del analista.

> ✅ **El backend ya está listo.** `ClaimResponse` expone el análisis forense estructurado (`forensicReport`) y cases-service sirve las imágenes de los adjuntos. Ver "Contrato de backend (disponible)" más abajo. El front puede arrancar sin esperar nada.

---

## Dónde va y quién lo ve

- **Solo en el panel del ANALISTA** (`ANALISTA_SINIESTROS`). Es el detalle del siniestro donde el analista revisa la recomendación del modelo y decide aprobar/rechazar.
- **El asegurado NUNCA ve esto.** El asegurado ve un estado simplificado del expediente, sin clasificación, sin IA, sin riesgo ni motivos internos. El análisis forense es información interna sensible: exponerlo al asegurado sería un problema (le estarías diciendo "sospechamos que tu foto es trucha" y cómo lo detectaste). Esta separación ya es una regla del proyecto.

---

## Qué mostrar

### 1. Sección "Análisis forense"

Un bloque dentro del detalle del siniestro, junto a la recomendación de clasificación. Solo aparece si hubo análisis (los Fast Track no lo tienen — ver estados abajo).

Estructura sugerida:

```
┌─ Análisis forense ─────────────────────────────────┐
│                                                     │
│  [imagen 1]   Comparación con siniestros previos:   │
│               ✓ Sin coincidencias                   │
│               Búsqueda en internet:                 │
│               ⚠ Publicada en 10 páginas             │
│                 · mercadolibre.com/...              │
│                 · amazon.com/...                    │
│               Google la identifica como:            │
│               "Nike Women's Run Swift 3"            │
│                                                     │
│  [imagen 2]   ✓ Sin hallazgos                       │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 2. Visualización de las imágenes analizadas junto al texto

- Cada imagen del siniestro, al lado de su análisis (no una galería suelta arriba y el texto abajo — que se lea imagen ↔ resultado).
- Si hubo un match interno, poder ver **la imagen del siniestro previo** con la que coincidió (comparación lado a lado) y el % de similitud.
- Si hubo match web, las páginas donde apareció como **links clickeables** (se abren en pestaña nueva).

### 3. Jerarquía visual por severidad

Que el analista capte de un vistazo dónde hay algo:

- 🔴 **Hallazgo fuerte** — match interno alto, o imagen en muchas páginas comerciales (foto de catálogo).
- 🟠 **Hallazgo a revisar** — pocas coincidencias parciales, o una sola página (podría ser la publicación del propio asegurado).
- 🟢 **Sin hallazgos** — se analizó y no se encontró nada. Mostrarlo igual: que el analista sepa que el control corrió.

---

## Estados a contemplar

| Estado | Qué pasó | Qué mostrar |
|---|---|---|
| **Fast Track** | El siniestro no pasó por análisis forense (lo resolvió el gate determinístico). | No mostrar la sección, o un texto tipo "No requiere análisis forense (Fast Track)". |
| **Sin imágenes** | El siniestro no tenía adjuntos de imagen. | "No se adjuntaron imágenes para analizar". |
| **Sin hallazgos** | Se analizó, todo limpio. | Sección en verde, "Sin coincidencias internas ni en internet". |
| **Hallazgo interno** | La imagen coincide con otro siniestro. | 🔴 con la imagen previa y el % de similitud. |
| **Hallazgo web** | La imagen está publicada en internet. | ⚠ con las páginas (links) y el `bestGuessLabel`. |
| **Análisis degradado** | Algún paso falló (pgvector caído, Vision no respondió). | Mostrar lo que sí se pudo, y marcar el paso que falló — NO ocultarlo (el analista tiene que saber que el control no corrió completo). |
| **Búsqueda web deshabilitada** | La integración externa está apagada para esa aseguradora. | Mostrar solo el resultado interno; no mostrar la parte web como "sin hallazgos" (no se buscó). |

---

## Consideraciones

- **Responsive obligatorio** (RNF de usabilidad: ≥85% de éxito en PC y móvil). En móvil, imagen arriba y análisis debajo; en desktop, lado a lado.
- **No es una decisión automática.** La UI tiene que dejar claro que esto es **apoyo** para el analista, no un veredicto. El analista sigue decidiendo aprobar/rechazar. Nada de "FRAUDE DETECTADO" en rojo grande — es una señal, no una conclusión.
- **`OnPush` + signals + SCSS**, standalone components, como el resto del front.
- **Labels en español** en la UI (los enums/valores vienen en inglés del back; el mapeo a español es responsabilidad del front).

---

## Contrato de backend (disponible)

Hoy el endpoint que el front pollea (`GET` del estado del siniestro → `ClaimResponse`) devuelve:

```
{ caseId, classification, confidence, factors: string[], deterministicFastTrack }
```

Las trazas del análisis forense hoy vienen **aplanadas dentro de `factors`**, mezcladas con los motivos de la clasificación, como texto suelto. Con eso **no se puede** construir la pantalla de arriba (no hay imágenes, no hay estructura, no se distingue un match interno de uno web, no hay URLs ni similitudes por separado).

**Todo el contrato de backend ya está disponible** — el front puede arrancar:

1. **Análisis forense estructurado** ✅ — `ClaimResponse` incluye `forensicReport` (ver contrato abajo), con `internalMatches`, `webFinding` y `webSearchesPerformed` por imagen. Se persiste en `classification_log` y viaja por el poll `GET /api/v1/claims/{caseId}`.
2. **Servir las imágenes** ✅ — cases-service persiste los adjuntos (`CaseDocument`, bytea) y los expone:
   - `GET /api/v1/cases/{caseId}/documents` → lista de documentos (metadata: `id`, `filename`, `contentType`, `size`).
   - `GET /api/v1/cases/{caseId}/documents/{documentId}` → los **bytes** con su `Content-Type`.

   El front renderiza directo: `<img [src]="'/api/v1/cases/' + caseId + '/documents/' + documentId">`. Para correlacionar cada `finding` del reporte con su imagen, matchear por `filename` contra la lista de documentos.
3. **"No se buscó" vs "se buscó y no hay"** ✅ — resuelto en el contrato: `webFinding == null` = no se buscó; `webFinding` presente con todo en 0 = se buscó y no encontró.

> **Nota:** la imagen del **siniestro previo** en un match interno (`matchedCaseId`) se sirve con el mismo endpoint apuntando a ese otro caso: `GET /api/v1/cases/{matchedCaseId}/documents/...`.

---

## Contrato ya disponible: `GET /api/v1/claims/{caseId}`

Devuelve el `ClaimResponse`. El campo nuevo es `forensicReport` (null si fue Fast Track o no había imágenes):

```jsonc
{
  "caseId": 9001,
  "classification": "LLM_SOLICITA_REVISION_MANUAL",
  "confidence": 0.61,
  "factors": ["...", "..."],
  "deterministicFastTrack": false,
  "forensicReport": {
    "imagesAnalyzed": 1,
    "webSearchesPerformed": 1,          // 0 = no se buscó en internet (distinto de "buscó y no encontró")
    "findings": [
      {
        "label": "damage_photo-0",
        "filename": "telefono.jpg",
        "internalMatches": [],           // vacío = sin coincidencias con siniestros previos
        "webFinding": {                  // null = NO se buscó en internet para esta imagen
          "fullMatches": 0,              // coincidencias exactas
          "partialMatches": 3,           // recortadas/redimensionadas
          "pages": [                     // páginas donde apareció (links clickeables)
            { "url": "https://www.instagram.com/reel/...", "title": "El iPhone 17e..." }
          ],
          "bestGuessLabel": "iphone 17 e que salio ahora"
        }
      }
    ]
  }
}
```

Para un match interno, `webFinding` es `null` (no se escaló) y `internalMatches` trae `{ matchedCaseId, matchedFilename, similarity }`.

### Cómo distinguir los estados desde el JSON

| Estado | Cómo detectarlo |
|---|---|
| Fast Track / sin análisis | `forensicReport == null` |
| Imagen limpia | `internalMatches` vacío **y** `webFinding.found()` falso (0/0/[]) |
| Match interno (reúso entre siniestros) | `internalMatches` no vacío |
| Match web (foto publicada) | `webFinding` con `fullMatches`/`partialMatches`/`pages` > 0 |
| Web no buscada | `webFinding == null` con `internalMatches` vacío → mostrar solo el resultado interno, **no** "sin hallazgos web" |

---

## Datos de ejemplo listos para desarrollar

Hay un seed que inserta 3 siniestros ya clasificados con análisis forense, uno por estado:

`classification-service/src/main/resources/db/seed-forensic-examples.sql`

| `caseId` | Escenario | Qué renderiza |
|---|---|---|
| **9001** | Rotura de celular, foto publicada en internet | `webFinding` con páginas (Instagram/TikTok) → 🔴/🟠 |
| **9002** | Foto reutilizada de otro siniestro (#8734) | `internalMatches` al 96% → 🔴 |
| **9003** | Foto genuina, sin hallazgos | todo vacío → 🟢 |

Para cargarlos (con la app levantada al menos una vez):
```bash
docker compose exec -T postgres psql -U arbiter -d arbiter < classification-service/src/main/resources/db/seed-forensic-examples.sql
```

Después, `GET http://localhost:8082/api/v1/claims/9001` (y 9002, 9003) ya devuelven los tres casos. Con eso el front tiene los tres estados para maquetar sin depender de Ollama ni del alta de siniestros.

---

## Tarea aparte (frontend) — Consentimiento en el wizard de alta

Esto **no** es del panel del analista, es del **wizard de alta de denuncia (asegurado)**, pero es responsabilidad del frontend y va de la mano con lo forense, así que queda anotado acá.

**Qué:** un checkbox de consentimiento para que la imagen del siniestro pueda enviarse a un proveedor externo de verificación antifraude (Google Vision). Es el **requisito legal** que habilita a prender la búsqueda en internet (`GOOGLE_VISION_ENABLED`) en producción.

**Por qué:** la búsqueda web saca la imagen del asegurado fuera de nuestra infraestructura → transferencia internacional de datos personales (Ley 25.326). Requiere consentimiento **libre, expreso e informado**.

**Requisitos de la UI:**
- Checkbox **dedicado y visible** en el paso final del wizard — no enterrado en unos T&C genéricos.
- Texto que informe **finalidad** (verificación antifraude), **quién** recibe (proveedor externo) y **dónde** (exterior).
- **Libre:** negarse **no** debe impedir denunciar. Si el asegurado no consiente, el flujo sigue igual; simplemente el backend no hace la búsqueda web (el factor queda no evaluable, la cascada ya lo contempla).
- Se suma al **bloque de consentimientos que ya estaba pendiente** en el wizard (el de WhatsApp) — misma sección.

**Backend:** persistir el consentimiento (con timestamp) es un pendiente aparte; hoy el flag es global por deployment. Coordinar cuando se implemente el alta con consentimiento por siniestro. Ver `docs/image-fraud-scoring-integration.md` (sección "Consentimiento") para el detalle legal.
