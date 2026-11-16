import { CaseStatus } from '../../../core/models/case-status';

/** A point in the diagram's own units (the SVG viewBox), not CSS pixels. */
export type Point = readonly [number, number];

/** The report that opens a case: not a status, but the diagram starts there. */
export const START = 'START';
export type LifecycleNode = CaseStatus | typeof START;

export const VIEW_BOX = { width: 1024, height: 486 };
export const NODE_WIDTH = 176;
export const NODE_HEIGHT = 28;
/** Gap between an arrowhead's tip and the box it points at. */
const TIP_GAP = 4;
export const START_POINT: Point = [130, 56];

/** Centre of each status box. Waiting on a third party sits under review; closures on the right. */
export const NODE_CENTER: Record<CaseStatus, Point> = {
  PENDING_CLASSIFICATION: [130, 120],
  CLASSIFICATION_FAILED: [130, 265],
  PENDING_ANALYST_REVIEW: [470, 190],
  PENDING_EXPERT_REPORT: [280, 400],
  PENDING_REPAIR: [470, 400],
  AWAITING_DOCUMENTATION: [660, 400],
  APPROVED: [900, 110],
  REJECTED: [900, 158],
  PRESCRIBED: [900, 206],
  LAPSED: [900, 318],
  WITHDRAWN: [900, 366],
  VOIDED: [900, 414],
};

export const VERDICT_GROUP = { x: 798, y: 70, width: 204, height: 160 };
export const NO_VERDICT_GROUP = { x: 798, y: 278, width: 204, height: 160 };

/**
 * One way into each status: `join` is where every line into it meets; the single arrowhead runs
 * from there to the side of the box named here. Lines run join to join, so they share stretches
 * instead of crossing boxes.
 */
type Side = 'left' | 'right' | 'top' | 'bottom';
const ENTRY: Record<CaseStatus, { join: Point; side: Side }> = {
  PENDING_CLASSIFICATION: { join: [244, 120], side: 'right' },
  CLASSIFICATION_FAILED: { join: [244, 265], side: 'right' },
  PENDING_ANALYST_REVIEW: { join: [470, 240], side: 'bottom' },
  PENDING_EXPERT_REPORT: { join: [280, 366], side: 'top' },
  PENDING_REPAIR: { join: [470, 366], side: 'top' },
  AWAITING_DOCUMENTATION: { join: [660, 366], side: 'top' },
  APPROVED: { join: [790, 110], side: 'left' },
  REJECTED: { join: [790, 158], side: 'left' },
  PRESCRIBED: { join: [790, 206], side: 'left' },
  LAPSED: { join: [790, 318], side: 'left' },
  WITHDRAWN: { join: [790, 366], side: 'left' },
  VOIDED: { join: [790, 414], side: 'left' },
};

function tipOf(status: CaseStatus, side: Side): Point {
  const [cx, cy] = NODE_CENTER[status];
  const dx = NODE_WIDTH / 2 + TIP_GAP;
  const dy = NODE_HEIGHT / 2 + TIP_GAP;
  switch (side) {
    case 'left':
      return [cx - dx, cy];
    case 'right':
      return [cx + dx, cy];
    case 'top':
      return [cx, cy - dy];
    case 'bottom':
      return [cx, cy + dy];
  }
}

export const PORT = Object.fromEntries(
  (Object.keys(ENTRY) as CaseStatus[]).map((status) => [
    status,
    { join: ENTRY[status].join, tip: tipOf(status, ENTRY[status].side) },
  ]),
) as Record<CaseStatus, { join: Point; tip: Point }>;

const y = (status: CaseStatus) => NODE_CENTER[status][1];

/**
 * The drawn route of each move, keyed `from>to`. A move back the other way reuses the same line,
 * so a pair that goes both ways is drawn once. A move missing here is drawn straight.
 */
export const ROUTES: Readonly<Record<string, readonly Point[]>> = {
  'START>PENDING_CLASSIFICATION': [START_POINT, [244, 56], [244, 120]],
  'PENDING_CLASSIFICATION>CLASSIFICATION_FAILED': [
    [244, 120],
    [244, 265],
  ],
  'PENDING_CLASSIFICATION>PENDING_ANALYST_REVIEW': [
    [244, 120],
    [320, 120],
    [320, 240],
    [470, 240],
  ],
  'PENDING_CLASSIFICATION>AWAITING_DOCUMENTATION': [
    [244, 120],
    [320, 120],
    [320, 300],
    [660, 300],
    [660, 366],
  ],
  'PENDING_ANALYST_REVIEW>PENDING_EXPERT_REPORT': [
    [470, 240],
    [280, 240],
    [280, 366],
  ],
  'PENDING_ANALYST_REVIEW>PENDING_REPAIR': [
    [470, 240],
    [470, 366],
  ],
  'PENDING_ANALYST_REVIEW>AWAITING_DOCUMENTATION': [
    [470, 240],
    [660, 240],
    [660, 366],
  ],
  'LAPSED>PENDING_ANALYST_REVIEW': [
    [790, y('LAPSED')],
    [770, y('LAPSED')],
    [770, 240],
    [470, 240],
  ],
  ...Object.fromEntries(
    (['APPROVED', 'REJECTED', 'PRESCRIBED', 'WITHDRAWN', 'VOIDED'] as const).map((to) => [
      `PENDING_ANALYST_REVIEW>${to}`,
      [
        [470, 240],
        [770, 240],
        [770, y(to)],
        [790, y(to)],
      ],
    ]),
  ),
  ...Object.fromEntries(
    (['WITHDRAWN', 'VOIDED'] as const).map((to) => [
      `CLASSIFICATION_FAILED>${to}`,
      [
        [244, 265],
        [780, 265],
        [780, y(to)],
        [790, y(to)],
      ],
    ]),
  ),
  ...Object.fromEntries(
    (['LAPSED', 'WITHDRAWN', 'VOIDED'] as const).map((to) => [
      `AWAITING_DOCUMENTATION>${to}`,
      [
        [660, 366],
        [760, 366],
        [760, y(to)],
        [790, y(to)],
      ],
    ]),
  ),
};

export function joinOf(node: LifecycleNode): Point {
  return node === START ? START_POINT : PORT[node].join;
}

/** The route a move takes and whether it runs against the way it is stored. */
export function routeOf(from: LifecycleNode, to: LifecycleNode): { key: string; back: boolean } {
  const key = `${from}>${to}`;
  if (ROUTES[key]) {
    return { key, back: false };
  }
  const reverse = `${to}>${from}`;
  return ROUTES[reverse] ? { key: reverse, back: true } : { key, back: false };
}

export function pointsOf(key: string): readonly Point[] {
  const [from, to] = key.split('>') as [LifecycleNode, LifecycleNode];
  return ROUTES[key] ?? [joinOf(from), joinOf(to)];
}

/** Orthogonal polyline with rounded corners. */
export function roundedPath(points: readonly Point[], radius = 9): string {
  let d = `M ${points[0][0]} ${points[0][1]}`;
  for (let i = 1; i < points.length - 1; i++) {
    const [px, py] = points[i - 1];
    const [cx, cy] = points[i];
    const [nx, ny] = points[i + 1];
    const l1 = Math.hypot(cx - px, cy - py);
    const l2 = Math.hypot(nx - cx, ny - cy);
    if (l1 === 0 || l2 === 0) {
      continue;
    }
    const k = Math.min(radius, l1 / 2, l2 / 2);
    const ax = cx - ((cx - px) / l1) * k;
    const ay = cy - ((cy - py) / l1) * k;
    const bx = cx + ((nx - cx) / l2) * k;
    const by = cy + ((ny - cy) / l2) * k;
    d += ` L ${ax} ${ay} Q ${cx} ${cy} ${bx} ${by}`;
  }
  const end = points[points.length - 1];
  return `${d} L ${end[0]} ${end[1]}`;
}

export function polylineLength(points: readonly Point[]): number {
  let length = 0;
  for (let i = 1; i < points.length; i++) {
    length += Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]);
  }
  return length;
}

/** The point halfway along a polyline, where a repeat count goes. */
export function midpointOf(points: readonly Point[]): Point {
  let remaining = polylineLength(points) / 2;
  for (let i = 1; i < points.length; i++) {
    const [ax, ay] = points[i - 1];
    const [bx, by] = points[i];
    const segment = Math.hypot(bx - ax, by - ay);
    if (remaining <= segment && segment > 0) {
      const t = remaining / segment;
      return [ax + (bx - ax) * t, ay + (by - ay) * t];
    }
    remaining -= segment;
  }
  return points[points.length - 1];
}

/** A walk through the diagram as one continuous polyline, each move in the direction it went. */
export function walkPoints(steps: readonly LifecycleNode[]): Point[] {
  const out: Point[] = [];
  for (let i = 0; i < steps.length - 1; i++) {
    const { key, back } = routeOf(steps[i], steps[i + 1]);
    const points = back ? [...pointsOf(key)].reverse() : pointsOf(key);
    for (const p of points) {
      const last = out[out.length - 1];
      if (!last || last[0] !== p[0] || last[1] !== p[1]) {
        out.push(p);
      }
    }
  }
  return out;
}
