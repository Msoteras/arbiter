# Bugs de interfaz

Defectos de UI encontrados probando la aplicación en vivo. No son decisiones de alcance
(`temas-a-discutir.md`) ni correcciones al modelo (`der-gaps.md`): son cosas que están mal y hay
que arreglar, con el diagnóstico ya hecho para que el arreglo no arranque de cero.

> La lista numerada que citan algunos comentarios del código (`bugs-ux #20`, `#22`) es el
> relevamiento de Aylén, que vive fuera del repo. Esto la continúa acá.

---

## Login — la nota de soporte queda ilegible sobre la cordillera

**Encontrado:** 28/08/2026, probando la solapa de trazabilidad después de mergear `develop`.

**Qué pasa:** el texto "¿No podés ingresar o necesitás una cuenta? Contactá a soporte." se
superpone con la imagen de la cordillera y pierde contraste hasta volverse ilegible.

**Por qué:** `.mtn` es `position: absolute; bottom: 0; width: 100%`, así que su altura depende del
ancho del viewport; `.support-note` usa `--text-secondary`, un gris cálido calibrado contra el
papel (`--surface`), no contra el teal de la imagen. En viewports anchos y de poca altura la
cordillera sube lo suficiente como para quedar detrás del texto, y ahí el par de colores no llega
al contraste AA que el design system se compromete a cumplir.

**Archivos:** `arbiter-frontend/src/app/features/auth/login/login.component.scss`
(`.mtn` línea 18, `.support-note` línea 97).

**A tener en cuenta al arreglarlo:** el media query de ≤768px ya reserva espacio con
`padding-bottom`, y el de alturas muy chicas directamente esconde la cordillera — el caso que se
escapa es el intermedio (ancho grande, alto medio). Conviene resolverlo por reserva de espacio o
por un scrim detrás del texto, no subiéndole el peso a la tipografía: el problema es el fondo, no
la fuente.
