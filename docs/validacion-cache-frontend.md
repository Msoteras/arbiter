# Validación del cache del frontend — 14/10/2026

Contra el stack local y la base de Railway, con Chrome sin ventana, después de integrar `develop`.
Las escrituras "de otra persona" se hicieron por API con otro token, sin pasar por la pestaña probada.

| # | Qué se probó | Resultado |
|---|---|---|
| C1 | Precarga del referente: al terminar el inicio (≈4,5 s) pide las 10 lecturas de D4 | OK |
| C2 | Ir a Usuarios después de la precarga, y volver | OK: 58 ms sin spinner; 37 ms la segunda vez. Antes, varios segundos contra Railway |
| C3 | Guardar Atribuciones, ir a otra pantalla y volver | OK: muestra el valor guardado |
| C4 | L1: otra persona cambia Fraude mientras esta pestaña tiene un cambio sin guardar | OK: el refresco no pisa lo tipeado; "Descartar" muestra el valor nuevo |
| C5 | D5: otra persona desasigna un expediente; el analista vuelve a la Bandeja | OK: aparece "Actualizar"; al tocarlo se aplica y desaparece |
| C6 | Bandeja visitada 3 veces sin cambios afuera | OK: nunca aparece "Actualizar" |
| C7 | Precarga del analista: primera página de la Bandeja con la misma clave que pide la pantalla, lentes, hechos generadores y analistas | OK |
| C8 | Precarga del asegurado: Mis expedientes, las dos listas de pólizas y el perfil; las tres pantallas abren sin spinner (390 px) | OK |
| C9 | D9: con `auth-service` caído, la precarga reintenta 6 veces sin toast; un clic del usuario sí lo muestra | OK |
| C10 | L2 (sesión que vence o cambia de usuario) y L5 (mutación de lo guardado) | Tests unitarios (`query-cache.spec.ts`) |
| C11 | Puesta en marcha sobre las pantallas combinadas con `develop`: los 13 links destacan su control | OK |
| C12 | Tests del front (297), lint en 0, build; tests de los 5 módulos Java | OK. El build avisa que el bundle inicial pasa el presupuesto de 500 kB por 247 bytes |

## Hallazgos

| # | Hallazgo | Estado |
|---|---|---|
| H1 | Con una respuesta sincrónica, `refresh()` devolvía `undefined` | Corregido antes del commit (lo encontró el test unitario) |
| H2 | Cada clave guardaba la primera función de pedido, no la última | Corregido |
| H3 | La rama estaba 32 commits atrás de `develop`. Railway ya tenía la migración que borra `brand`, `model` e `imei` de `document_analysis`, así que el código viejo de la rama fallaba al desasignar o abrir un expediente | Corregido: merge de `develop` en la rama |
