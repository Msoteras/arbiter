# Handoff — tablero de métricas del referente

**Fecha:** 12/09/2026 · **Actualizado:** 13/09/2026, al mergear develop · **Rama:** `feature/metricas-referente`

Este archivo es lo que **falta**, no lo que se hizo. Se borra cuando se vacíe.

---

## Dónde quedó

**`feature/metricas-referente`** — **PR sin abrir**, y ése es el próximo paso.
Ocho commits propios: el enum compartido, el filtro de la bandeja, el objetivo configurable, el
esquema, el backend de métricas, el tablero, la historia H0037 y este handoff.

**Develop mergeado el 13/09** con la liquidación (PR #84) y las pruebas y fixes de septiembre (#85)
adentro. Seis conflictos, resueltos en el commit del merge. **892 tests del backend y 120 del
frontend en verde después del merge.**

`feature/determinacion-pagos` ya entró a develop, así que deja de ser una rama a seguir.

**H0028** es la historia que se implementó: **cerrar su card en Trello cuando el PR entre a develop**.
El backlog de historias ya no vive en el repo — develop borró `docs/historias-enhancements.md` porque
las cards estaban duplicadas en Trello.

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

### 2 · ~~El servicio técnico no puede cargar su presupuesto~~ — resuelta en develop

Era la única decisión que hacía que el sistema no siguiera el procedimiento al pie de la letra, y la
cerró develop antes que nosotros: `ExpertAssessmentService.receiveRepairReport` ya recibe el
`repairCost` del taller —presupuestado o facturado— y llega a la liquidación como el monto
acreditado de la fórmula de reparación. Queda anotado acá sólo para que no se vuelva a abrir.

---

## Lo que falta, por lo que rinde

1. **Fast Track: cuánto agiliza de verdad.** La maqueta pedía "54 h ahorradas", que es un número
   modelado (diferencia de dos promedios × 3 casos) y con esa cantidad de casos es ruido. La versión
   honesta es la comparación cruda: **"Fast Track: 2 d · Resto: 35 d"**, dos números medidos, uno al
   lado del otro. Backend: separar el promedio de resueltos por la marca de Fast Track.
2. **Cumplimiento del plazo legal (art. 56).** Más importante que el objetivo interno: uno es una
   meta que la compañía se pone, el otro es la ley. Cada expediente ya trae su fecha límite.
   **Verificar primero** que nuestro reinicio sea el del procedimiento: cuando la documentación se
   completa, *"comenzará a regir nuevamente el tiempo legal de 30 días"* — vuelven a correr 30 desde
   cero, no se reanuda lo que quedaba.
3. **Tasa de reapertura.** El procedimiento tiene el estado REHABILITADO y nosotros ya contemplamos
   la reapertura al fechar la resolución. Es una métrica de *calidad de la decisión*: si se reabren
   muchos, se está decidiendo rápido y mal. Se lee junto con la coincidencia con el modelo.
4. **Monto aprobado** — **destrabado**: `case_settlement.settled_amount` entró a develop con el PR
   #84 y ya está en esta rama. Es el pendiente más maduro de la lista, y del que cuelgan los dos
   siguientes.
5. **Siniestros Pendientes de Liquidación (reserva expuesta).** El procedimiento la nombra como
   control del Jefe de Siniestros, trimestral. Es la plata que sigue sobre la mesa. Necesita el
   punto 4.
6. **Plazo de pago**: 15 días desde que se acepta el siniestro. Segundo plazo legal que hoy no se
   mide en absoluto. Necesita lo mismo.
7. **H0037 — tablero propio del analista.** **Falta cargarla como card en Trello**: se escribió en
   `docs/historias-enhancements.md` justo antes de que develop borrara ese archivo, así que el texto
   completo quedó sólo en el commit `f1bd3483` (`git show f1bd3483`). El acceso ya existe (la ruta y
   el endpoint lo habilitan); lo que falta es el recorte a sus expedientes.

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
