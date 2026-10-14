# Handoff — mejoras de UX en Configuración de reglas

**Para:** Fede · **Rama:** `feature/ux-enhancements-reglas` (con `develop` mergeado al 13/10/2026)

El objetivo fue que la pantalla de reglas del referente sea consistente con lo que la base permite
configurar, y que use el kit en vez de controles hechos a mano.

## Estado de la rama

- **Commiteado y pusheado:**
  - `39307fc5` — solapa Derivaciones y borrado de `document_requirement.mandatory`.
  - `6aee4553` — botones y solapas del kit, proveedores, descripción de cobertura y borrado de
    `coverage.is_individual`.
- **Sin commitear** (está en el working tree): borrado de `score_band.active`, última solapa
  recordada, montos con formato en el historial, limpieza de tooltips de Fast Track y los specs
  nuevos del front.

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

### Coberturas

- Campo "Descripción" en el editor, que viaja en alta y edición.

### Proveedores externos

- Alta y edición en panel lateral; lapicito y tacho en vez de botones con texto.
- Un proveedor nuevo arranca con todos los ramos marcados y se guarda siempre la lista explícita.
  Sin ninguno marcado no deja guardar.

### Kit

- `app-tabs` (solapas del ramo) y `app-icon-button` (editar, borrar, confirmar), los dos en
  `/styleguide` y en el spec de responsive.
- Los chips hechos a mano pasaron a `app-checkbox`.
- En teléfono, el switch de cada regla queda al lado del título.

### Detalles de uso

- La pantalla recuerda la última solapa: ya no vuelve a Coberturas al abrir otro ramo ni al
  recargar.
- El historial muestra el monto de peritaje como pesos ("$ 500.000").
- Fast Track: salieron dos tooltips y una aclaración que repetían lo que la pantalla ya dice; el
  tooltip del título ahora dice "Definí las condiciones para que un expediente vaya por Fast Track."

## Lo que tenés que hacer vos

1. **Aplicar tres migraciones en Railway**, después de desplegar el código. Las tres son
   idempotentes:
   - `db/migrations/2026-10-13-borrar-obligatorio-de-la-agenda.sql`
   - `db/migrations/2026-10-13-borrar-es-individual-de-cobertura.sql`
   - `db/migrations/2026-10-13-borrar-activo-de-banda-de-puntaje.sql`
2. **Editar el `.mdj`** con las entradas 23 y 25 de `docs/der-gaps.md`: dropear `obligatorio` de
   `requisito_documental` y `activo` de `configuracion_banda`.
3. **Revisar y commitear** lo que quedó en el working tree.

## Cómo quedó verificado

- **Backend:** tests unitarios de `rules-service`, `cases-service` y `classification-service` en
  verde. Los de Testcontainers no se corrieron.
- **Front:** lint y Prettier limpios; 306 tests en verde, 42 de ellos nuevos (ver "Tests del
  front").
- **En el navegador, contra Railway:** Derivaciones probada de punta a punta (guardar, recargar,
  restaurar). La última solapa, los montos del historial y los tooltips se verificaron leyendo. El
  resto se probó abriendo y cancelando, sin guardar.
- **Sin probar en pantalla:** guardar un proveedor con la lista explícita de ramos, guardar una
  descripción de cobertura, y los botones de ícono de las listas de texto.

### Tests del front

Specs nuevos, todos con los servicios mockeados:

| Spec | Qué cubre |
|---|---|
| `shared/ui/tabs/tabs.component.spec.ts` | Selección por clic, flechas, Inicio y Fin, y que solo la solapa activa quede en el orden de tabulación. |
| `shared/ui/icon-button/icon-button.component.spec.ts` | Nombre accesible y tooltip, tono peligro del borrar, estado deshabilitado. |
| `features/admin/derivations-config/…spec.ts` | Carga, validación del monto, que solo se mande la regla que cambió, apagar conservando el monto, descarte, cambio de ramo y errores. |
| `features/admin/providers-config/…spec.ts` | Todos los ramos marcados por defecto, bloqueo sin ramos, lista explícita al guardar, edición y confirmación de borrado. |
| `features/admin/rules/rules.component.spec.ts` | Última solapa recordada, que no se pierda al cambiar de ramo, descripción de la cobertura y checkboxes. |

Lo que sigue sin test: el guardado de coberturas con descripción contra el backend y los botones
de ícono dentro de las listas de texto.

## Decisiones abiertas para el equipo

- **Reparación:** si pasa a ser un dato del hecho generador ("es un daño reparable") en vez de una
  regla por ramo. Según lo que se decida, se descomenta o se borra junto con su endpoint.
- **`document_requirement.risk_band`:** implementarla o borrarla. Está anotada como entrada 24 en
  `der-gaps.md`, con los problemas de pedir documentos según la banda.
- **Proveedor generalista:** el backend todavía trata una lista vacía de ramos como "cubre todos".
  La pantalla ya no la manda, pero los proveedores viejos sin ramos siguen así hasta que alguien los
  guarde.
- **Acceso de solo lectura para analistas** a esta pantalla: pedido, sin empezar. Hoy casi todas
  las lecturas exigen rol referente.
- **Interruptor del puntaje de riesgo:** `scoring_configuration.active` apaga el puntaje
  configurado y el motor lo lee, pero la pantalla lo manda siempre en `true`. Decidir si se expone
  como switch o se deja fijo.

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

- **Plazo de denuncia:** la base guarda horas y la pantalla edita días con división entera. Un
  plazo de 36 h se muestra como 1 día y se guarda como 24 h al tocar cualquier otro campo de la
  cobertura. Es el único que puede pisar un dato.
- **Reglas apagadas de la cobertura:** el campo de valor queda visible con el switch apagado; en
  Fast Track y Hard Stop se reemplaza por un texto.
- **Alta y baja de ramos y hechos generadores:** descartado para esta iteración.

## Mejoras de UX detectadas, sin hacer

1. **Los cambios sin guardar se pierden sin aviso** al cambiar de ramo o salir de la pantalla. No
   hay confirmación ni guard de navegación.
2. **Si falla la carga de hechos generadores**, la pantalla muestra "No hay hechos generadores en
   este ramo", como si el ramo estuviera vacío. Debería mostrar el error con un reintento.
3. **La matriz de agenda documental en teléfono** corta el último encabezado y obliga a scroll
   horizontal.
4. **Puntaje de riesgo:** la nota "Pesos relativos · no hace falta que sumen 100 · «pesa %» = peso
   sobre los factores activos" es difícil de leer, y el campo de peso de cada factor no tiene
   etiqueta ni unidad.
5. **Tooltips que quedaron:** conviene repasarlos con el mismo criterio que los de Fast Track. El
   candidato más claro es el de "Hechos generadores que cubre".
6. **Tabs hechas a mano en otras pantallas** (Reportes, detalle del expediente, bandeja, chat): se
   pueden migrar a `app-tabs`.
7. **A revisar:** en una de las pruebas la barra de guardar seguía en "Cambios sin guardar" unos
   segundos después de un guardado que sí había entrado. No quedó claro si fue demora de Railway o
   un problema de la barra.

Para empezar, el 1: es el que más puede costarle trabajo al referente.
