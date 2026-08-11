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
