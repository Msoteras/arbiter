# Validación de la puesta en marcha — 14/10/2026

Contra el stack local (`docker-compose.railway.yml` + Gemini) y la base de Railway. Navegador:
Chrome sin ventana, manejado por script. Los guardados son reales en La Segunda, salvo el alta de
analistas y de asegurados: esas dos se simularon para no mandar invitaciones, y el aviso a
`rules-service` se hizo con un token de servicio, igual que lo hace `auth-service`.

## Plan y resultado

| # | Qué se probó | Resultado |
|---|---|---|
| V1 | Permisos de la API: referente de las 3 aseguradoras 200; analista 403; sin token 401; descartar un obligatorio 422; paso inexistente 400; reinicio por analista 403; `touches` con token de usuario 403 | OK. El 422 decía «No se usa» → **F1** |
| V2 | Los 13 pasos, en orden, con un guardado real cada uno: estado DONE, toast "Paso completo", "Siguiente" lleva al próximo pendiente y destaca su control | OK los 13. Fraude no se podía completar con el valor por defecto → **F3**. Tiempos del toast → **F2** |
| V3 | Después de reiniciar, un paso con datos sigue pendiente hasta guardar su sección (C1) | OK |
| V4 | Descartar y deshacer; el reinicio deshace los descartes | OK |
| V5 | Ubicación de la tarjeta: arriba con un obligatorio pendiente, compacta debajo de las métricas después, oculta con todo completo | OK. Grupos vacíos con título → **F4** |
| V6 | Puntos del menú: aparecen por área con pendientes y desaparecen sin pendientes | OK |
| V7 | BBVA (11 de 13) y Provincia (11 de 14, dos ramos con pólizas) ven la tarjeta; los analistas no ven tarjeta, puntos ni "Reiniciar" | OK |
| V8 | Cada link de la tarjeta abre su pantalla y destaca su control | OK |
| V9 | "Siguiente" hacia la misma dirección, hacia otra pantalla y dentro de Usuarios | OK |
| V10 | Teléfono (390 px): sin desplazamiento horizontal; tarjeta, toast y destaque | OK. Destaque de una sección alta → **F5** |
| V11 | `cases-service` caído: la tarjeta dice "No se pudo cargar la puesta en marcha." y "Reintentar" funciona al volver | OK |
| V12 | Tests: `rules`, `cases`, `auth`, `classification`, `reports`; front; lint; build | OK. Lint vuelve a correr (faltaba `npm install`) |

## Hallazgos corregidos

| # | Hallazgo | Arreglo |
|---|---|---|
| F1 | El 422 y Swagger decían «No se usa» | "Este paso es obligatorio: no se puede descartar." |
| F2 | `GET /rules/onboarding` tardaba ~15 s desde la notebook | `cases-service` en paralelo, una sola transacción de lectura y los hechos generadores leídos una vez por ramo: 4-5,5 s. Lo que queda son 25 consultas a ~170 ms cada una entre la notebook y Railway; dentro de Railway la latencia es ~1 ms |
| F3 | Gestión de fraude sin guardar mostraba 36 meses con "Todo guardado" y "Guardar" deshabilitado: el paso no se podía completar sin cambiar el número | Una política que nunca se guardó cuenta como cambio sin guardar |
| F4 | Un grupo sin pendientes mostraba su título solo | La tarjeta muestra un grupo solo si tiene algo para mostrar |
| F5 | En el teléfono, el destaque de una sección más alta que la pantalla mostraba su mitad | Una sección alta se alinea por arriba, debajo de la barra superior |
