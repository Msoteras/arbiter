# Handoff — tablero de métricas del referente

**Fecha:** 12/09/2026 · **Ramas:** `feature/metricas-referente` (nueva) y `feature/determinacion-pagos` (actualizada)

Este archivo es lo que **falta**, no lo que se hizo. Se borra cuando se vacíe.

---

## Dónde quedó

**`feature/metricas-referente`** — pusheada, al día con develop, **PR sin abrir**.
Siete commits: el enum compartido, el filtro de la bandeja, el objetivo configurable, el esquema,
el backend de métricas, el tablero y la historia H0037. **884 tests del backend, 116 del frontend.**

**`feature/determinacion-pagos`** — pusheada con develop mergeado adentro. **892 y 113.**
El merge tuvo cinco conflictos; los cinco resueltos (ver "Lo que decidió el procedimiento").

**H0028** (`docs/historias-enhancements.md`) es la historia que se implementó. **Tacharla como hecha
cuando el PR entre a develop**, igual que las otras.

---

## Cómo levantarlo

```bash
docker compose up -d --build reports-service rules-service cases-service
cd arbiter-frontend && npm start
```

Entrar como `referente.arbiter@gmail.com` → **Dashboard**. Ver también **Reglas → Reglas generales →
Objetivo de resolución**, que es lo que alimenta la tarjeta de tiempo.

> **El objetivo quedó en 21 días cargado en la base compartida de Railway**, tenant BBVA, para poder
> probarlo de punta a punta. Si molesta a alguien del equipo se apaga desde ese mismo panel.

---

## Dos decisiones abiertas

### 1 · ¿Contra qué se mide el objetivo de 21 días?

Hoy se compara contra el **tiempo total** (reloj de pared, denuncia → decisión). El procedimiento de
la compañía dice que pedir documentación o derivar a un perito **interrumpe** el plazo, así que lo
fiel sería compararlo contra el **tiempo de gestión**. No se cambió por cuenta propia porque
alteraría el significado de "3 de 7 lo superaron" después de que ya se vio funcionando.

El tablero ya calcula las dos mitades, así que es cambiar cuál de las dos entra en la comparación.

### 2 · El servicio técnico no puede cargar su presupuesto

El procedimiento trata las tres valuaciones por igual —preinforme del perito, informe técnico,
presupuesto— pero **el endpoint del servicio técnico no pide monto**, así que hoy sólo el perito
puede cargarlo. Es un endpoint, un campo en el formulario y su test; está anotado en el código en
los dos lados (`ExpertAssessmentService.finishRound` y el componente de detalle).

---

## Lo que falta, por lo que rinde

1. **El presupuesto del servicio técnico** (la decisión 2 de arriba). Es lo único que hoy hace que
   el sistema no siga el procedimiento al pie de la letra.
2. **Fast Track: cuánto agiliza de verdad.** La maqueta pedía "54 h ahorradas", que es un número
   modelado (diferencia de dos promedios × 3 casos) y con esa cantidad de casos es ruido. La versión
   honesta es la comparación cruda: **"Fast Track: 2 d · Resto: 35 d"**, dos números medidos, uno al
   lado del otro. Backend: separar el promedio de resueltos por la marca de Fast Track.
3. **Cumplimiento del plazo legal (art. 56).** Más importante que el objetivo interno: uno es una
   meta que la compañía se pone, el otro es la ley. Cada expediente ya trae su fecha límite.
   **Verificar primero** que nuestro reinicio sea el del procedimiento: cuando la documentación se
   completa, *"comenzará a regir nuevamente el tiempo legal de 30 días"* — vuelven a correr 30 desde
   cero, no se reanuda lo que quedaba.
4. **Tasa de reapertura.** El procedimiento tiene el estado REHABILITADO y nosotros ya contemplamos
   la reapertura al fechar la resolución. Es una métrica de *calidad de la decisión*: si se reabren
   muchos, se está decidiendo rápido y mal. Se lee junto con la coincidencia con el modelo.
5. **Monto aprobado** — bloqueado hasta que `feature/determinacion-pagos` entre a develop.
   `case_settlement.settled_amount` existe sólo en esa rama.
6. **Siniestros Pendientes de Liquidación (reserva expuesta).** El procedimiento la nombra como
   control del Jefe de Siniestros, trimestral. Es la plata que sigue sobre la mesa. Necesita lo
   anterior.
7. **Plazo de pago**: 15 días desde que se acepta el siniestro. Segundo plazo legal que hoy no se
   mide en absoluto. Necesita lo mismo.
8. **H0037 — tablero propio del analista.** Escrita en `docs/historias-enhancements.md`. El acceso
   ya existe (la ruta y el endpoint lo habilitan); lo que falta es el recorte a sus expedientes.

**Descartado, no pospuesto:** la línea de backlog ("abiertos al cierre" por semana). Es la consulta
más cara de todas y con una docena de casos al mes sería una línea plana; el embudo ya responde lo
mismo con "10 expedientes del período siguen abiertos".

---

## Lo que decidió el procedimiento, no nosotros

Del merge con develop salieron dos preguntas de negocio. Las contestó `Siniestros_NSIN001.docx`:

> §2.7 — "ajustará la reserva de acuerdo a las valuaciones recibidas a través de **los preinformes de
> estudios liquidadores, informes técnicos, o presupuestos** que se reciban en el tiempo de
> resolución **hasta su liquidación**"
>
> §5.2.1.2 — "El análisis de los informes finales recibidos de los **Estudios Liquidadores y
> Servicios**... para su liquidación"

Las tres fuentes valen igual y el analista ajusta contra **la última que llega**. Por eso
`SettlementService` toma la valuación más reciente venga de quien venga, en vez de preferir al
perito o de elegir según la fórmula.

---

## Definiciones que hay que respetar si se toca esto

No son preferencias, son las que hacen que los números cierren entre pantallas:

- **El embudo y el resumen cuentan poblaciones distintas.** El embudo sigue a los que ENTRARON en el
  período; el resumen cuenta los que CERRARON en él. Confundirlos es la forma más fácil de leer mal
  el tablero.
- **Los caducados quedan fuera de las tasas y del promedio.** Nadie los decidió: midieron el
  silencio del asegurado. Incluirlos mueve el promedio decenas de días.
- **Un expediente reabierto y vuelto a cerrar cuenta una vez**, el día que cerró definitivamente. Es
  la misma definición que usa el reporte de resoluciones de Flor — cambiarla en un lado desincroniza
  los dos.
- **La coincidencia con el modelo se mide contra el estado final**, no contra el texto de la
  decisión: esa columna convive en dos idiomas (`APPROVE` desde la app, `APROBAR` desde el seed).
- **Un Fast Track no pasa por el modelo.** La base lo prohíbe con un CHECK, así que el paso
  "analizados" del embudo siempre es menor que el ingreso, por diseño.

---

## Trampas que ya costaron tiempo

- **`mvn` sin `-am` usa un `common-lib` viejo** y tira decenas de errores que no tienen nada que ver
  con lo que tocaste. Si aparece algo absurdo (`cannot access CauseConsistency`), correr con `-am`
  antes de investigar.
- **`-Pit` corre SOLO los tests etiquetados.** Hay que correr las dos: `mvn test` y `mvn test -Pit`.
- **`ng serve` lee `proxy.conf.json` al arrancar.** Ruta nueva = reiniciar el front.
- **El shell no acolcha el contenido**: cada pantalla pone su `padding` en el `:host`. Al reescribir
  un `.scss` desde cero se pierde y la pantalla queda pegada a la topbar.
- **`app-loading` es el overlay de pantalla completa** del arranque login → home. Dentro de una
  pantalla que ya tiene su shell va `app-inline-loading`.
- **No deserializar con Jackson sobre un record con campos que el remoto no manda.** Falla en
  runtime, no en compilación. El cliente se arma su propio record espejo (ver `RulesServiceClient`).
- **ngx-echarts descarta el primer aviso de su ResizeObserver**, que es justo el que trae el ancho
  real cuando el gráfico se arma mientras la tarjeta anima su entrada. `app-chart` tiene el suyo.
- **La sesión del front vence rápido** mientras se prueba. El botón "Entrar" funciona; Enter en el
  campo de contraseña no submitea.
