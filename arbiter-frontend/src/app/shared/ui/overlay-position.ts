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
  minWidth: number | null;
  maxWidth: number | null;
}

/**
 * `stretch` = at least as wide as the trigger, growing toward the side with more room (selects);
 * `start`/`end` = anchored to that edge (menus).
 */
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

  // A stretched panel wider than its trigger grows away from the nearer viewport edge.
  const roomRight = viewportWidth - trigger.left - GAP;
  const roomLeft = trigger.right - GAP;
  const anchorEnd = align === 'end' || (align === 'stretch' && roomLeft > roomRight);

  // Pin the edge opposite to the opening side so the panel need not be measured before rendering.
  return {
    top: dropUp ? null : trigger.bottom + GAP,
    bottom: dropUp ? viewportHeight - trigger.top + GAP : null,
    left: anchorEnd ? null : trigger.left,
    right: anchorEnd ? viewportWidth - trigger.right : null,
    minWidth: align === 'stretch' ? trigger.width : null,
    maxWidth:
      align === 'stretch' ? Math.max(trigger.width, anchorEnd ? roomLeft : roomRight) : null,
  };
}

export interface PanelPosition {
  top: number | null;
  bottom: number | null;
  left: number;
  maxHeight: number;
}

/**
 * For a panel wider than its trigger (pickers). Keeps the panel inside the viewport horizontally and
 * caps its height to the side it opens to, so on a phone it scrolls instead of leaving the screen.
 */
export function anchorPanel(trigger: DOMRect, height: number, width: number): PanelPosition {
  const viewportWidth = document.documentElement.clientWidth;
  const viewportHeight = document.documentElement.clientHeight;
  const spaceBelow = viewportHeight - trigger.bottom - 2 * GAP;
  const spaceAbove = trigger.top - 2 * GAP;
  const dropUp = spaceBelow < height && spaceAbove > spaceBelow;
  const maxLeft = Math.max(GAP, viewportWidth - width - GAP);
  return {
    top: dropUp ? null : trigger.bottom + GAP,
    bottom: dropUp ? viewportHeight - trigger.top + GAP : null,
    left: Math.min(Math.max(GAP, trigger.left), maxLeft),
    maxHeight: dropUp ? spaceAbove : spaceBelow,
  };
}
