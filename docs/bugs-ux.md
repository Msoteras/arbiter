# Bugs de interfaz

Defectos de UI encontrados probando la aplicación en vivo. No son decisiones de alcance
(`temas-a-discutir.md`) ni correcciones al modelo (`der-gaps.md`): son cosas que están mal y hay
que arreglar, con el diagnóstico ya hecho para que el arreglo no arranque de cero.

> La lista numerada que citan algunos comentarios del código (`bugs-ux #20`, `#22`) es el
> relevamiento de Aylén, que vive fuera del repo. Esto la continúa acá.

---

## Login — la nota de soporte quedaba ilegible sobre la cordillera

**Encontrado:** 28/08/2026. **Mitigado:** 29/08/2026. **Queda una vuelta pendiente.**

**Qué pasaba:** el texto "¿No podés ingresar o necesitás una cuenta? Contactá a soporte." se
superponía con la cordillera y perdía contraste hasta volverse ilegible.

**Por qué:** `.mtn` es `position: absolute; bottom: 0; width: 100%`, así que su alto sale de la
proporción del PNG (0,435) y **lo manda el ancho de la pantalla**: a 1900px de ancho mide 827 de
alto y le sube por encima al texto. En pantallas angostas no se nota — de ahí que el media query de
mobile ya reservara espacio con `padding-bottom` y el caso que faltaba fuera el inverso.

**Cómo quedó:** la nota tiene papel propio (`background: var(--surface-sunken)` + padding + radio
pill) y tinta plena. Sobre el papel la pastilla es invisible (mismo color); sobre la montaña
aparece como halo y el texto se lee contra fondo sólido. Contraste real 15,4 contra los 2,93 de
antes. El layout no se tocó.

**Dos intentos que no funcionaron, para no repetirlos:**

1. **Sacar la imagen de `absolute` y ponerla en el flujo** como última fila de la columna, para que
   su alto lo mandara el espacio sobrante. Funciona y no tiene números mágicos, pero cambia
   bastante el layout y a Fede no le cerró visualmente.
2. **Subir solo el contraste del texto** (`--text-primary`): daba 4,72 y pasaba AA en las 35
   combinaciones de pantalla medidas (900–2560 de ancho × 700–1200 de alto)… y **seguía sin
   leerse**. El ratio de contraste asume fondo sólido; acá el fondo es una textura ditherizada, y
   la varianza entre nieve y roca rompe la legibilidad aunque el número cierre. Vale como
   recordatorio de que la métrica no alcanza sola cuando el fondo es una imagen.

**Lo que queda:** Fede quedó a medias conforme — la idea es **bajar la cordillera** para que no le
llegue al texto y la pastilla deje de hacer falta. Si se hace, conviene medir dónde queda el borde
superior de la imagen en pantallas anchas antes y después, porque su alto depende del ancho.
