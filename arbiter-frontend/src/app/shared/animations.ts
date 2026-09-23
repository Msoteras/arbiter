import { animate, query, stagger, style, transition, trigger } from '@angular/animations';

/**
 * Entry animations fire on `:enter`, so bind them to the node that appears with the data already
 * loaded, not to one wrapping the spinner. `@angular/animations` ignores `prefers-reduced-motion`;
 * `App` disables them for the whole tree with `[@.disabled]`.
 */

/**
 * Staggered entry of a container's direct children. Ends at `transform: 'none'`, not
 * `translateY(0)`: the runtime keeps the final style, and any transform turns the element into the
 * containing block of its `position: fixed` descendants (info tips, select panels).
 */
export const staggerReveal = trigger('staggerReveal', [
  transition(':enter', [
    query(
      ':scope > *',
      [
        style({ opacity: 0, transform: 'translateY(10px)' }),
        stagger(70, [animate('420ms ease-out', style({ opacity: 1, transform: 'none' }))]),
      ],
      { optional: true },
    ),
  ]),
]);

/** Ends at `transform: 'none'` for the same reason as {@link staggerReveal}. */
export const fadeInUp = trigger('fadeInUp', [
  transition(':enter', [
    style({ opacity: 0, transform: 'translateY(8px)' }),
    animate('320ms ease-out', style({ opacity: 1, transform: 'none' })),
  ]),
]);

/** Faster, subtler `staggerReveal` for list/table rows, which are many. */
export const listStagger = trigger('listStagger', [
  transition(':enter', [
    query(
      ':scope > *',
      [
        style({ opacity: 0, transform: 'translateY(6px)' }),
        stagger(45, [
          animate('300ms cubic-bezier(0.16, 1, 0.3, 1)', style({ opacity: 1, transform: 'none' })),
        ]),
      ],
      { optional: true },
    ),
  ]),
]);

/**
 * Opacity-only `listStagger` for rows inside a scroll container, where a `translateY` causes a
 * transient overflow that flickers the scrollbar.
 */
export const fadeStagger = trigger('fadeStagger', [
  transition(':enter', [
    query(
      ':scope > *',
      [style({ opacity: 0 }), stagger(40, [animate('260ms ease-out', style({ opacity: 1 }))])],
      { optional: true },
    ),
  ]),
]);

/** Re-runs on every value change (`* => *`): `[@tabSwitch]="activeTab()"`. */
export const tabSwitch = trigger('tabSwitch', [
  transition('* => *', [
    style({ opacity: 0, transform: 'translateY(6px)' }),
    animate('260ms cubic-bezier(0.16, 1, 0.3, 1)', style({ opacity: 1, transform: 'none' })),
  ]),
]);

/** `animate` without an end style keeps the bound width, so the bar grows from 0 to it. */
export const growBar = trigger('growBar', [
  transition(':enter', [
    style({ width: '0%' }),
    animate('700ms 120ms cubic-bezier(0.16, 1, 0.3, 1)'),
  ]),
]);

/**
 * `margin-top` is animated too: otherwise the panel's own margin appears at once and the content
 * below jumps. Closing is deliberately shorter than opening.
 */
export const accordion = trigger('accordion', [
  transition(':enter', [
    style({ height: 0, opacity: 0, marginTop: 0, overflow: 'hidden' }),
    animate(
      '240ms cubic-bezier(0.16, 1, 0.3, 1)',
      style({ height: '*', opacity: 1, marginTop: '*' }),
    ),
  ]),
  transition(':leave', [
    style({ overflow: 'hidden' }),
    animate('180ms ease-in', style({ height: 0, opacity: 0, marginTop: 0 })),
  ]),
]);
