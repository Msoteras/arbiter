# Handoff — mejoras de UX en Configuración de reglas

**Para:** Fede · **Rama:** `feature/ux-enhancements-reglas` (con `develop` mergeado al 14/10/2026)

El objetivo fue que la pantalla de reglas del referente sea consistente con lo que la base permite
configurar, y que use el kit en vez de controles hechos a mano.

## Estado de la rama

- **Commiteado y pusheado:**
  - `39307fc5` — solapa Derivaciones y borrado de `document_requirement.mandatory`.
  - `6aee4553` — botones y solapas del kit, proveedores, descripción de cobertura y borrado de
    `coverage.is_individual`.
  - `a9dce38c` — última solapa recordada, montos con formato en el historial, limpieza de tooltips
    de Fast Track, borrado de `score_band.active` y specs nuevos del front.
- **Merge de `develop` commiteado** (PR #144). Ver "Merge con develop" más abajo.

## Qué se hizo

### Derivaciones (solapa nueva por ramo)

- Switch de derivación a peritaje con monto mínimo reclamado. Antes solo se cambiaba por SQL.
- Endpoints nuevos para el referente en `rules-service`: `GET` y `PUT` de
  `/api/v1/rules/expert-derivation` y `/api/v1/rules/repair-derivation`. Cada cambio queda en el
  historial.
- La parte de reparación está **comentada en el template**; el endpoint y la lógica del componente
  siguen ahí sin uso.

### Columnas borradas

| Columna | Por qué |
|---|---|
| `document_requirement.mandatory` | Siempre valía `TRUE` y nadie la leía. |
| `coverage.is_individual` | Repetía "alcanza al grupo familiar" y nadie la leía. |
| `score_band.active` | Las bandas se reescriben en cada guardado, siempre en `TRUE`, y nadie filtraba por ella. |
| `scoring_configuration.active` | El puntaje de riesgo está siempre activo (decidido el 14/10). El motor ahora puntúa siempre que la aseguradora tenga una configuración usable; antes, con el flag apagado, el expediente quedaba sin puntaje. |

### Coberturas

- Campo "Descripción" en el editor, que viaja en alta y edición.

### Proveedores externos

- Alta y edición en panel lateral; lapicito y tacho en vez de botones con texto.
- Un proveedor nuevo arranca con todos los ramos marcados. Sin ninguno marcado no deja guardar: un
  proveedor sin ramos no cubre ninguno (regla que trajo `develop`, ver abajo).

### Kit

- `app-tabs` (solapas del ramo) y `app-icon-button` (editar, borrar, confirmar), los dos en
  `/styleguide` y en el spec de responsive. `app-tabs` acepta un estado por solapa que dibuja un
  punto después de la etiqueta.
- Los chips hechos a mano pasaron a `app-checkbox`.
- En teléfono, el switch de cada regla queda al lado del título.

### Detalles de uso

- La pantalla recuerda la última solapa: ya no vuelve a Coberturas al abrir otro ramo ni al
  recargar.
- El historial muestra el monto de peritaje como pesos ("$ 500.000").
- Fast Track: salieron dos tooltips, una aclaración y el badge "Siempre activo". El tooltip del
  título usa el texto de la guía de primera carga: "Definí las condiciones de una denuncia para ser
  considerada Fast Track." (se cambia en `core/onboarding/onboarding.ts`).

## Merge con develop (14/10)

`develop` traía trabajo de Valentina sobre las mismas pantallas (guía de primera carga, carga de
reglas más segura, proveedores sin generalistas). Hubo cuatro conflictos y se resolvieron así:

| Dónde | Cómo quedó |
|---|---|
| Proveedores | La regla de `develop` (sin generalistas, al menos un ramo; migración ya aplicada en Railway) más "proveedor nuevo arranca con todos los ramos marcados". |
| Solapas del ramo | `app-tabs` con los puntos de estado de `develop`: Coberturas avisa cuando el ramo no tiene coberturas y Documentación cuando algún hecho cubierto no tiene documentos. Aparecen siempre que se cumple la condición, no solo durante la guía. |
| Título de Fast Track | Tooltip con el texto de la guía de primera carga, sin el badge "Siempre activo". |
| Documentos de Fast Track | La lógica de `develop` con checkboxes y sin tooltip: solo ofrece documentos de la agenda de los hechos cubiertos, marca en rojo los que quedaron afuera y no deja guardar hasta quitarlos. |

Además hubo que:

- Sumar el argumento `description` a un test nuevo de `develop` (`OnboardingNotificationTest`) que
  armaba un pedido de cobertura sin ese campo.
- Proveer router y HTTP en los specs de reglas y proveedores, que ahora usan la guía de primera
  carga, y mockear los nombres de cobertura de las pólizas.
- Reinstalar `common-lib` en `~/.m2` para correr los servicios locales con `run-local`: con el jar
  viejo `rules-service` no arranca.

`develop` también resolvió dos ítems que teníamos pendientes: la confirmación antes de descartar
cambios sin guardar y los textos descriptivos de Puntaje, Fraude y Atribuciones.

## Lo que tenés que hacer vos

1. ~~**Commitear el merge** con `develop` y el borrado de `scoring_configuration.active`.~~ Hecho
   (PR #144).
2. ~~**Aplicar cuatro migraciones en Railway**~~. Hecho, verificado el 15/10:
   - `db/migrations/2026-10-13-borrar-obligatorio-de-la-agenda.sql`
   - `db/migrations/2026-10-13-borrar-es-individual-de-cobertura.sql`
   - `db/migrations/2026-10-13-borrar-activo-de-banda-de-puntaje.sql`
   - `db/migrations/2026-10-14-borrar-activo-de-configuracion-de-puntaje.sql`
3. **Editar el `.mdj`** con las entradas 23, 25 y 26 de `docs/der-gaps.md`: dropear `obligatorio` de
   `requisito_documental`, `activo` de `configuracion_banda` y `activa` de `configuracion_scoring`.
   Sigue pendiente: va con la card "actualizar DER".
4. ~~**Abrir el PR** de `feature/ux-enhancements-reglas` contra `develop`.~~ Hecho.

## Cómo quedó verificado

- **Backend:** tests unitarios de `rules-service`, `cases-service` y `classification-service` en
  verde después del merge. Los de Testcontainers no se corrieron.
- **Front:** lint, Prettier y build limpios; 350 tests en verde después del merge.
- **En el navegador, contra Railway (antes del merge):** Derivaciones probada de punta a punta
  (guardar, recargar, restaurar). La última solapa, los montos del historial y los tooltips se
  verificaron leyendo. El resto se probó abriendo y cancelando, sin guardar.
- **Sin ver en pantalla:** todo lo que resolvió el merge (puntos de las solapas, documentos de Fast
  Track con checkboxes, tooltip nuevo), guardar un proveedor, guardar una descripción de cobertura,
  y los botones de ícono de las listas de texto.

### Tests del front

Specs nuevos, todos con los servicios mockeados:

| Spec | Qué cubre |
|---|---|
| `shared/ui/tabs/tabs.component.spec.ts` | Selección por clic, flechas, Inicio y Fin, y que solo la solapa activa quede en el orden de tabulación. |
| `shared/ui/icon-button/icon-button.component.spec.ts` | Nombre accesible y tooltip, tono peligro del borrar, estado deshabilitado. |
| `features/admin/derivations-config/…spec.ts` | Carga, validación del monto, que solo se mande la regla que cambió, apagar conservando el monto, descarte, cambio de ramo y errores. |
| `features/admin/providers-config/…spec.ts` | Todos los ramos marcados al crear, bloqueo sin ramos, lista al guardar, edición y confirmación de borrado. |
| `features/admin/rules/rules.component.spec.ts` | Última solapa recordada, que no se pierda al cambiar de ramo, descripción de la cobertura y checkboxes. |

Lo que sigue sin test: el guardado de coberturas con descripción contra el backend, los botones de
ícono dentro de las listas de texto y los puntos de estado de `app-tabs`.

## Decisiones abiertas para el equipo

- **Reparación:** si pasa a ser un dato del hecho generador ("es un daño reparable") en vez de una
  regla por ramo. Según lo que se decida, se descomenta o se borra junto con su endpoint. Se discute
  en la reunión del fin de semana.

**Decidido el 14/10:**

- El puntaje de riesgo está siempre activo. Ya está hecho: se borró `scoring_configuration.active`
  (ver "Columnas borradas").
- **Pasan a historias** (en Trello): el acceso de solo lectura de los analistas a la configuración
  de reglas y la implementación de `document_requirement.risk_band`.

## Cómo funciona la trazabilidad del puntaje de riesgo

Vale aclararlo porque se presta a confusión:

- Hay **una sola fila de configuración por aseguradora**, que se pisa en cada guardado. No hay
  varias versiones conviviendo con una marcada como activa.
- La trazabilidad está en `scoring_configuration_history`: cada guardado copia ahí la versión
  anterior completa (pesos, bandas y flags), con vigencia, fecha y autor.
- El motor guarda el id de la configuración que calculó cada puntaje, pero ese id es siempre el
  mismo. Para saber con qué pesos se puntuó un expediente viejo hay que cruzar su fecha con la
  vigencia del historial.

## Pendientes de la lista original

- ~~**Plazo de denuncia**~~: hecho (PR #152). La pantalla edita horas, igual que la base, y ya no
  pisa un plazo de 36 h.
- ~~**Reglas apagadas de la cobertura**~~: hecho en `fix/reglas-pendientes-ux`. Con el switch
  apagado, el campo se reemplaza por un texto ("Sin carencia", "Sin plazo", "Sin tope"), como en
  Fast Track y Hard Stop. El resumen de la cobertura plegada tampoco muestra el valor de una regla
  apagada.
- **Alta y baja de ramos y hechos generadores:** descartado para esta iteración.

## Mejoras de UX detectadas

Hechas:

1. ~~**Si falla la carga de hechos generadores**~~ (PR #152): la pantalla muestra "No se pudo
   cargar el ramo." con un reintento. Cubre toda la carga del ramo.
2. ~~**La matriz de agenda documental en teléfono**~~ (`fix/reglas-pendientes-ux`): los
   encabezados de los hechos generadores van en vertical y la tabla entra sin scroll.
3. ~~**Puntaje de riesgo**~~ (`fix/reglas-pendientes-ux`): el peso de cada factor tiene la
   etiqueta "Peso". No lleva unidad: los pesos son relativos y el "pesa N %" de al lado muestra la
   parte que aplica el motor.
4. ~~**Tooltips que quedaron**~~ (`fix/reglas-pendientes-ux`): salió el de "Hechos generadores que
   cubre", que repetía el título. Queda el aviso de cuando la cobertura cubre todo el ramo.

Sin hacer:

5. **Tabs hechas a mano en otras pantallas** (Reportes, detalle del expediente, bandeja): se
   pueden migrar a `app-tabs`.
6. **A revisar:** en una de las pruebas la barra de guardar seguía en "Cambios sin guardar" unos
   segundos después de un guardado que sí había entrado. No quedó claro si fue demora de Railway o
   un problema de la barra.

Ya resueltos por `develop`: la confirmación antes de descartar cambios sin guardar al cambiar de
ramo o de sección.
