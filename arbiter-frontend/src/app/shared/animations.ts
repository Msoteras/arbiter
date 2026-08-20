import { animate, query, stagger, style, transition, trigger } from '@angular/animations';

/**
 * Animaciones reutilizables de la app, con la DSL de {@code @angular/animations}. Se aplican con
 * el binding {@code @nombre} en el template del componente que las declare en {@code animations: []}.
 *
 * Son entradas breves y sutiles (un fade + unos píxeles de desplazamiento). Nota: el runtime de
 * {@code @angular/animations} NO desactiva solo con {@code prefers-reduced-motion} — si más
 * adelante hace falta, se corta a nivel de app con un binding {@code [@.disabled]} atado a la
 * media query. La pantalla de carga (app-loading), que es la animación más notoria, sí la respeta.
 */

/**
 * Entrada escalonada de los hijos DIRECTOS de un contenedor: suben unos píxeles y aparecen, uno
 * detrás del otro. Pensada para el contenido de una pantalla ni bien termina de cargar (ej. el
 * saludo, las tarjetas y las columnas del inicio). Se dispara al montarse el contenedor ({@code
 * :enter}).
 */
export const staggerReveal = trigger('staggerReveal', [
  transition(':enter', [
    query(
      ':scope > *',
      [
        style({ opacity: 0, transform: 'translateY(10px)' }),
        stagger(70, [animate('420ms ease-out', style({ opacity: 1, transform: 'translateY(0)' }))]),
      ],
      { optional: true },
    ),
  ]),
]);

/** Aparición simple (fade + leve subida) para un bloque suelto, sin escalonar hijos. */
export const fadeInUp = trigger('fadeInUp', [
  transition(':enter', [
    style({ opacity: 0, transform: 'translateY(8px)' }),
    animate('320ms ease-out', style({ opacity: 1, transform: 'translateY(0)' })),
  ]),
]);

/**
 * Entrada escalonada más rápida y sutil, pensada para FILAS de una lista/tabla (los ítems de
 * "Requieren tu acción", las filas de la bandeja): un fade con 6px de subida, escalonadas de a
 * poco. Se aplica sobre el contenedor de las filas ({@code @listStagger} en el {@code <ul>}/
 * {@code <tbody>}). Diferencia con {@code staggerReveal}: menos desplazamiento y menor delay,
 * porque las filas son muchas y una entrada larga se sentiría lenta.
 */
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
 * Igual que {@code listStagger} pero SOLO fade (sin desplazamiento vertical). Pensada para filas
 * dentro de un contenedor con scroll (ej. la tabla de la bandeja): un {@code translateY} ahí genera
 * un desborde transitorio que hace parpadear la barra de scroll mientras dura la animación. Con solo
 * opacidad, las filas aparecen sin mover el layout, así que la tabla no "salta" ni muestra scroll.
 */
export const fadeStagger = trigger('fadeStagger', [
  transition(':enter', [
    query(
      ':scope > *',
      [
        style({ opacity: 0 }),
        stagger(40, [animate('260ms ease-out', style({ opacity: 1 }))]),
      ],
      { optional: true },
    ),
  ]),
]);

/**
 * Fade + leve subida del panel activo cada vez que cambia su clave (ej. al cambiar de pestaña en el
 * detalle del expediente). A diferencia de {@code fadeInUp} (solo {@code :enter}), esta reanima en
 * cada cambio de estado ({@code * => *}), así el contenido "entra" en cada tab. Se aplica con un
 * binding de valor: {@code [@tabSwitch]="activeTab()"}.
 */
export const tabSwitch = trigger('tabSwitch', [
  transition('* => *', [
    style({ opacity: 0, transform: 'translateY(6px)' }),
    animate('260ms cubic-bezier(0.16, 1, 0.3, 1)', style({ opacity: 1, transform: 'none' })),
  ]),
]);

/**
 * Crecimiento horizontal de una barra al montarse: va de ancho 0 hasta el ancho ligado por el
 * componente ({@code [style.width.%]}). {@code animate} sin estilo final conserva el ancho
 * computado del elemento, así que la barra "crece" hasta su valor real. Pensada para las barras
 * de la distribución de la bandeja en el inicio del analista ({@code @growBar} en cada relleno).
 */
export const growBar = trigger('growBar', [
  transition(':enter', [
    style({ width: '0%' }),
    animate('700ms 120ms cubic-bezier(0.16, 1, 0.3, 1)'),
  ]),
]);

/**
 * Apertura y cierre de un acordeón: la altura crece desde 0 hasta la real ({@code height: '*'}),
 * con un fade que acompaña.
 *
 * También se anima el {@code margin-top}, no solo la altura: el panel tiene margen propio, y sin
 * eso el espacio aparecía de golpe al empezar la animación y el contenido de abajo pegaba un salto
 * antes de que el panel llegara a abrirse.
 *
 * El cierre es más corto que la apertura (180 vs 240ms) y con {@code ease-in}: al abrir se está
 * esperando a ver algo, al cerrar ya se decidió y demorarlo se siente lento.
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
