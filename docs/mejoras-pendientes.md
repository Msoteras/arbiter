# Mejoras que encontré y no son de la historia

Anotadas durante la validación del 14/10/2026. Ninguna está implementada.

## De la puesta en marcha y Reglas

- **Lectura de `insurer_rule` en una sola consulta.** `GET /rules/onboarding` hace 13 consultas a
  `insurer_rule` (una por cobertura y por ramo para Fast Track, exclusiones y textos). Leer todas las
  filas de la aseguradora de una vez bajaría el tiempo desde la notebook de ~4 s a ~1,5 s. No lo hice
  porque duplica cómo cada servicio interpreta su `configuration`.
- **Salir de Reglas descarta cambios sin avisar.** El modal "¿Descartar los cambios sin guardar?"
  solo aparece al cambiar de ramo o de sección dentro de Reglas. Ir a otra pantalla por el menú o por
  "Siguiente" del toast pierde el borrador. Un `canDeactivate` lo resolvería.
- **Puntaje permite un factor prendido con peso 0.** Se guarda sin aviso y el factor no aporta nada.
  Pedir un peso mayor que 0 al prenderlo.
- **El toast "Paso completo" tarda de 7 a 10 s desde la notebook.** Es la suma de la espera para
  agrupar pedidos (400 ms), el guardado y la lectura de la puesta en marcha. En Railway va a ser
  mucho menos; desde la notebook se nota.
- **En el teléfono, "Descartar" ocupa una fila entera debajo de cada paso.** Se podría llevar a la
  misma línea que la flecha.

## Del cache

- **El bundle inicial pasa el presupuesto de 500 kB por 247 bytes.** Es una advertencia del build. Se
  puede subir el presupuesto o sacar del bundle principal algo que no haga falta al arrancar.
- **Liquidaciones pendientes no tuvo prueba en el navegador**: La Segunda no tiene liquidaciones por
  autorizar. Usa el mismo mecanismo que la Bandeja, que sí se probó.

## Otras pantallas

- **"Ninguna póliza de este ramo tiene esta cobertura."** La cobertura Robo de Celulares en La Segunda
  muestra ese aviso: su nombre no coincide con ninguno de las pólizas. Es un dato de prueba, pero el
  aviso no dice qué hacer.
