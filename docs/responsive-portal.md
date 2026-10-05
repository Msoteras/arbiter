# Responsive del portal del asegurado — bugs encontrados y corregidos

Relevamiento hecho con Chromium headless sobre las pantallas del portal (inicio, nueva denuncia,
mis pólizas, mis siniestros, seguimiento, documentación, perfil, onboarding y mensajes), logueado
como asegurado, en los anchos de la matriz de testeo:

| Dispositivo           | Ancho |
|-----------------------|-------|
| iPhone SE             | 375px |
| iPhone 12/13/14       | 390px |
| Android gama media (Pixel 5) | 393px |
| iPad / tablet         | 768px |

Todos los bugs de esta lista están corregidos y verificados en 375, 390 y 393px.

## Bugs

**1. Barra superior con scroll interno**

- **Pantalla y ancho:** todas las del portal, 375–393px.
- **Qué pasaba:** con tres links (Inicio, Mis siniestros, Mensajes) la barra no entraba; "Mensajes"
  quedaba cortado ("Men…") y la barra scrolleaba hacia el costado.
- **Causa:** se sumó un tercer link y la barra tenía `overflow-x: auto`.
- **Arreglo:** en phone los links pasan a un menú ☰ que se abre debajo de la barra.

**2. Panel de notificaciones salido de pantalla**

- **Pantalla y ancho:** todas, 375px.
- **Qué pasaba:** el panel se abría unos 16px fuera del borde izquierdo y se cortaba.
- **Causa:** estaba anclado a la campana con ancho de 88% de la pantalla, y la campana queda cerca
  del borde derecho.
- **Arreglo:** en phone ocupa el ancho de la barra, de borde a borde.

**3. Formulario angosto dentro del modal de nueva denuncia**

- **Pantalla y ancho:** wizard de nueva denuncia, 375–393px.
- **Qué pasaba:** el formulario quedaba en unos 229px útiles a 375px, con márgenes enormes a los
  costados.
- **Causa:** la tarjeta del wizard sumaba su propio margen de 32px al del modal.
- **Arreglo:** se quitó el margen duplicado cuando el wizard está dentro del modal (también en
  escritorio).

**4. Botones de navegación del wizard fuera de pantalla**

- **Pantalla y ancho:** wizard de nueva denuncia, todos; se nota en el paso 2, el más largo.
- **Qué pasaba:** "Volver / Cancelar / Continuar" quedaban al final del formulario y había que
  scrollear todo para llegar.
- **Causa:** la barra de botones era parte del contenido que scrollea.
- **Arreglo:** la barra de botones queda fija abajo, en el modal y en `/new-claim`.

**5. Aviso "Falta completar" apretado**

- **Pantalla y ancho:** wizard de nueva denuncia, 327–393px.
- **Qué pasaba:** el aviso quedaba en una columna angosta al lado de "Continuar", partido en cuatro
  líneas.
- **Causa:** el aviso y el botón compartían la misma fila.
- **Arreglo:** el aviso va en su propia línea arriba de los botones, y "Continuar" ocupa el ancho
  sobrante.

**6. Círculos del stepper desparejos**

- **Pantalla y ancho:** wizard de nueva denuncia, 375–393px.
- **Qué pasaba:** los círculos 1-2-3 no quedaban a la misma distancia entre sí.
- **Causa:** se repartían según el largo de cada etiqueta ("Datos" vs "Documentación").
- **Arreglo:** tres columnas iguales con el círculo centrado en cada una.

**7. Zoom automático en tablet**

- **Pantalla y ancho:** perfil, onboarding y mis siniestros, 768px.
- **Qué pasaba:** los campos medían 13px; por debajo de 16px el iPad hace zoom al tocarlos.
- **Causa:** la regla de 16px se cortaba a los 640px de ancho sin mirar si el dispositivo era
  táctil.
- **Arreglo:** el zoom al enfocar se desactiva en iPhone/iPad y los campos quedan en 14px en todo
  dispositivo táctil.

**8. Selector "por página" a 12px**

- **Pantalla y ancho:** mis siniestros, todos.
- **Qué pasaba:** el selector de la paginación medía 12px, con el mismo problema de zoom.
- **Causa:** usaba un tamaño fijo, sin regla para mobile.
- **Arreglo:** igual que el anterior.

**9. Campos más grandes que el resto del formulario**

- **Pantalla y ancho:** wizard de nueva denuncia, 375–393px.
- **Qué pasaba:** los campos y el desplegable estaban a 16px en un formulario donde todo lo demás
  mide 12–14px.
- **Causa:** los 16px eran la forma de evitar el zoom de iOS.
- **Arreglo:** pasaron a 14px; el zoom se evita desactivándolo en iPhone/iPad.

**10. Botones de acción de los modales que se iban con el scroll**

- **Pantalla y ancho:** todos los modales, todos los anchos.
- **Qué pasaba:** scrolleaba el modal entero, incluidos el título y los botones.
- **Causa:** el scroll estaba en el contenedor del modal y no en su contenido.
- **Arreglo:** cabecera y botones fijos; solo scrollea el contenido.

**11. Links de la barra, campana y avatar chicos**

- **Pantalla y ancho:** todas, todos.
- **Qué pasaba:** medían 39px, 34px y 40px de alto.
- **Causa:** no tenían alto mínimo para uso táctil.
- **Arreglo:** 44px de alto mínimo en dispositivos táctiles.

**12. Botones del kit chicos**

- **Pantalla y ancho:** todas, todos.
- **Qué pasaba:** medían 38px de alto.
- **Causa:** no tenían alto mínimo para uso táctil.
- **Arreglo:** 44px de alto mínimo en dispositivos táctiles.

**13. Botón ✕ de los modales chico**

- **Pantalla y ancho:** todos los modales, todos.
- **Qué pasaba:** medía 18×20px.
- **Causa:** el área tocable era solo el carácter.
- **Arreglo:** 44×44px en dispositivos táctiles.

**14. "Ver coberturas" y "Pólizas vencidas" chicos**

- **Pantalla y ancho:** mis pólizas, todos.
- **Qué pasaba:** medían 17px y 21px de alto.
- **Causa:** eran botones de texto sin alto mínimo.
- **Arreglo:** 44px de alto mínimo en dispositivos táctiles.

**15. Paginación y filtros chicos**

- **Pantalla y ancho:** mis siniestros, todos.
- **Qué pasaba:** los botones de paginación medían 28px de alto, el selector "por página" 26px y
  los filtros 42px.
- **Causa:** no tenían alto mínimo para uso táctil.
- **Arreglo:** 44px de alto mínimo; en phone los dos botones de paginación se reparten el ancho.

**16. Links de volver chicos**

- **Pantalla y ancho:** seguimiento y documentación, todos.
- **Qué pasaba:** "‹ Mis siniestros" y "‹ Volver al seguimiento" medían 18px de alto.
- **Causa:** eran links de texto sin alto mínimo.
- **Arreglo:** 44px de alto mínimo en dispositivos táctiles.

**17. Botón "Ver mis documentos" angosto**

- **Pantalla y ancho:** inicio, 375–393px.
- **Qué pasaba:** medía 152px de ancho debajo del botón primario, que ocupaba todo el ancho.
- **Causa:** al apilarse en columna, solo el primario estaba configurado para ocupar el ancho.
- **Arreglo:** los dos ocupan el ancho completo.
