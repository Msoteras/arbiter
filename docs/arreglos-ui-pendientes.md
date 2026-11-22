# Arreglos de UI pendientes — revisión del 20/11/2026

Tres detalles vistos en producción (`arbiter-prod.up.railway.app`) durante la prueba de carga con el
siniestro #67 de Camila Ferreyra. Los tres están en `main`: la rama `feature/motor-sin-label` va 51
commits atrás y no tiene `app-spotlight`.

| # | Pantalla | Qué se ve | Esfuerzo |
|---|---|---|---|
| 1 | Portal del asegurado · detalle del siniestro | Encabezado con doble borde, distinto de las cards de abajo | Chico, a decidir |
| 2 | Analista · detalle del expediente | "Ver los 4" con tres criterios a la vista | Una línea |
| 3 | Analista · detalle del expediente | "Sin pedido de documentación" se lee como "no tiene documentos" | Un texto, en dos lugares |

---

## 1 · Bordes del encabezado en el detalle del siniestro (portal)

**Dónde:** `features/portal/case-tracking/case-tracking.component.html`, el bloque
`<app-spotlight class="hero">` ("Tu siniestro está en análisis.").

**Qué pasa:** `app-spotlight` dibuja dos cajas, una dentro de la otra
(`shared/ui/spotlight/spotlight.component.ts`):

| Caja | Radio | Borde | Fondo |
|---|---|---|---|
| Marco exterior (`app-spotlight`) | `--radius-frame` (22px) | `--border-default` | `--surface-sunken`, con 6px de separación (`--spotlight-gap`) |
| Panel interior (`.sl-hero`) | `--radius-panel` (16px) | `--border-subtle` + `--shadow-card` | `--surface` |

Los radios son concéntricos (22 − 6 = 16), así que el marco está bien armado. El problema es la
convivencia: justo debajo, "Seguimiento" y "Consultas sobre tu siniestro" son `app-card` de 12px, sin
marco. En la misma pantalla se ven dos lenguajes de borde, y el encabezado parece una caja dentro de
otra caja.

**Qué hacer:** confirmar primero qué criterio se aplicó en las pantallas que ya se arreglaron
(commits `294503cb` "las cajas anidadas siguen la esquina de la card que las contiene" y `dab11536`
"todas las cards de la web tienen relieve"). Dos caminos:

- Una variante de `app-spotlight` sin marco exterior para el portal, así el encabezado es una sola
  card, como las de abajo. Si es un input nuevo, va a la styleguide.
- Si el marco es el estilo buscado, aplicarlo también a las cards del portal, para que la pantalla
  tenga un solo lenguaje.

---

## 2 · "Ver los 4" con tres criterios a la vista

**Dónde:** `features/cases/case-detail/case-detail.component.html`, panel "Criterios de Fast Track"
(línea 75 en adelante), y `asideRows = 3` en `case-detail.component.ts` (línea 446).

**Qué pasa:** un caso de Fast Track evalúa cuatro criterios. El panel muestra los tres primeros y
el link "Ver los 4" lleva a la solapa de reglas. El que queda oculto es **Documentación exigida para
Fast Track**, justo el que configura la referente y el que se cuenta en el caso 1 de la demo. Con
tres a la vista, "Ver los 4" obliga a contar para entender qué falta.

**Qué hacer:** cuando sobra uno solo, mostrar todos. Por ejemplo, mostrar la lista entera si
`fastTrackCriteria().length <= asideRows + 1`, o subir `asideRows` a 4. El link queda para cinco o más.
Revisar que el panel de razones (línea 100, "Ver los {{ analysisReasons().length }}") siga la misma
regla.

---

## 3 · "Sin pedido de documentación" → "Sin pedido de documentación adicional"

**Dónde:**

- `features/cases/case-detail/case-detail.component.html`, línea 140 (la línea de resumen de un
  caso Fast Track).
- `features/styleguide/styleguide.component.ts`, línea 1323 (la vitrina del design system).

**Qué pasa:** la línea aparece solo cuando el caso es Fast Track. Quiere decir que no hace falta
pedirle nada más al asegurado, pero se lee como que el expediente no tiene documentación. El
asegurado ya entregó lo que exige el Fast Track (en el #67, el acta y la factura).

**Qué hacer:** cambiar el texto a "Sin pedido de documentación adicional" en los dos lugares, así la
styleguide no diverge de la pantalla real.
