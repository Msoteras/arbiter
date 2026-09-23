/**
 * Viewport coordinates for a floating panel anchored to its trigger. Panels are `fixed`, not
 * `absolute`, because any overflow ancestor clips them: `overflow-x: auto` (tables) clips
 * vertically too, since the spec forbids one axis `visible` when the other is not.
 */
export interface OverlayPosition {
  top: number | null;
  bottom: number | null;
  left: number | null;
  right: number | null;
  width: number | null;
}

/** `stretch` = same width as the trigger (selects); `start`/`end` = anchored to that edge (menus). */
export type OverlayAlign = 'start' | 'end' | 'stretch';

const GAP = 4;

export function anchorToTrigger(
  trigger: DOMRect,
  estimatedHeight: number,
  align: OverlayAlign,
): OverlayPosition {
  // clientWidth/Height, not innerWidth/Height: the latter include the scrollbar, which `fixed`
  // does not resolve against, shifting the panel by its width.
  const viewportWidth = document.documentElement.clientWidth;
  const viewportHeight = document.documentElement.clientHeight;

  const spaceBelow = viewportHeight - trigger.bottom;
  const dropUp = spaceBelow < estimatedHeight && trigger.top > spaceBelow;

  // Pin the edge opposite to the opening side so the panel need not be measured before rendering.
  return {
    top: dropUp ? null : trigger.bottom + GAP,
    bottom: dropUp ? viewportHeight - trigger.top + GAP : null,
    left: align === 'end' ? null : trigger.left,
    right: align === 'end' ? viewportWidth - trigger.right : null,
    width: align === 'stretch' ? trigger.width : null,
  };
}
