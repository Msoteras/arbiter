/**
 * Posiciona un panel flotante anclado a su trigger, en coordenadas de viewport.
 *
 * Los paneles del kit son `fixed` y no `absolute` porque cualquier ancestro con overflow los
 * recorta, y acá pasa seguido: un contenedor con `overflow-x: auto` (las tablas) recorta también
 * en vertical, porque la spec no permite dejar un eje en `visible` si el otro no lo está.
 */
export interface OverlayPosition {
  top: number | null;
  bottom: number | null;
  left: number | null;
  right: number | null;
  width: number | null;
}

/** `stretch` = mismo ancho que el trigger (selects); `start`/`end` = anclado a ese borde (menús). */
export type OverlayAlign = 'start' | 'end' | 'stretch';

const GAP = 4;

export function anchorToTrigger(
  trigger: DOMRect,
  estimatedHeight: number,
  align: OverlayAlign,
): OverlayPosition {
  // clientWidth/Height y no innerWidth/Height: estos incluyen la barra de scroll, contra la que
  // `fixed` no resuelve — el panel quedaba corrido su ancho (~15px).
  const viewportWidth = document.documentElement.clientWidth;
  const viewportHeight = document.documentElement.clientHeight;

  const spaceBelow = viewportHeight - trigger.bottom;
  const dropUp = spaceBelow < estimatedHeight && trigger.top > spaceBelow;

  // Se fija el borde opuesto al lado hacia el que abre: así no hace falta medir el panel antes
  // de renderizarlo.
  return {
    top: dropUp ? null : trigger.bottom + GAP,
    bottom: dropUp ? viewportHeight - trigger.top + GAP : null,
    left: align === 'end' ? null : trigger.left,
    right: align === 'end' ? viewportWidth - trigger.right : null,
    width: align === 'stretch' ? trigger.width : null,
  };
}
