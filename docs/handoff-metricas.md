# Handoff — tablero de métricas del referente

**Fecha:** 12/09/2026 · **Actualizado:** 13/09/2026, con las métricas que faltaban · **Rama:** `feature/metricas-referente`

Este archivo es lo que **falta**, no lo que se hizo. Se borra cuando se vacíe.

---

## Dónde quedó

**`feature/metricas-referente`** — **PR sin abrir**, y ése es el próximo paso.

**Develop mergeado el 13/09** con la liquidación (PR #84) y las pruebas y fixes de septiembre (#85)
adentro; seis conflictos, resueltos en el commit del merge. Encima entraron las ocho métricas que
faltaban (ver más abajo) y el objetivo medido contra el tiempo de gestión.

**486 tests del backend y 122 del frontend en verde**, unitarios e integración.

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

## Las dos decisiones que estaban abiertas — cerradas

### 1 · ~~¿Contra qué se mide el objetivo de 21 días?~~ — **tiempo de gestión**

Decidido el 13/09. Se compara contra el tiempo de gestión: al total se le descuenta lo que el
expediente esperó documentación, un perito o el servicio técnico, porque el procedimiento dice que
esas derivaciones interrumpen el plazo. Medido contra el reloj de pared, el objetivo le imputaba a
la gestión semanas que ni la ley ni el procedimiento le imputan, y daba un número que el referente
no podía accionar: un expediente se pasaba del objetivo por haber pedido un peritaje, que es
exactamente lo que debía hacer.

**Los números del tablero cambiaron de significado con esto**: "3 de 7 lo superaron" ahora cuenta
otra cosa que antes. La tarjeta lo dice — "Objetivo: 21 d **de gestión**" —, porque el número grande
de arriba sigue siendo el tiempo total y sin esa palabra la cuenta no cierra: un promedio de 35 días
contra un objetivo de 21 que casi todos cumplieron se lee como un error.

### 2 · ~~El servicio técnico no puede cargar su presupuesto~~ — resuelta en develop

Era la única decisión que hacía que el sistema no siguiera el procedimiento al pie de la letra, y la
cerró develop antes que nosotros: `ExpertAssessmentService.receiveRepairReport` ya recibe el
`repairCost` del taller —presupuestado o facturado— y llega a la liquidación como el monto
acreditado de la fórmula de reparación. Queda anotado acá sólo para que no se vuelva a abrir.

---

## Las ocho métricas que faltaban — hechas el 13/09

Las seis de la lista original que tenían dato, más dos que salieron al revisar qué guardamos:

1. **Cumplimiento del plazo legal (art. 56).** La única métrica regulatoria del panel. Se verificó
   primero lo que este handoff pedía verificar: el reinicio ya es el del procedimiento —al cumplirse
   el requerimiento vuelven a correr 30 días enteros, no los que quedaban— así que la métrica lee la
   fecha límite del expediente en vez de rehacer la cuenta.
2. **Monto liquidado y promedio por siniestro.** Sólo las liquidaciones firmadas.
3. **Reclamado contra liquidado**, con las tres deducciones (franquicia, cuotas, mora).
4. **Fraude determinado y lo que evitó pagar.** Es el número que justifica investigar: sin él,
   derivar a un perito figura sólo como demora.
5. **Respuesta de los terceros**: cuántas derivaciones salieron, cuántas siguen afuera y cuánto
   tardan en volver, separadas por peritaje y servicio técnico. Estaba escondido adentro del "tiempo
   esperando a terceros", que decía cuánto pero no a quién.
6. **Cuánto agiliza el Fast Track**, como dos promedios medidos uno al lado del otro.
7. **Tasa de reapertura**, al lado de la coincidencia con el modelo.
8. **Qué reglas frenan más expedientes**, que le dice al referente cuál de las que configuró está
   mordiendo.

## Lo que queda

- **H0037 — tablero propio del analista** (card en Trello). El acceso ya existe: la ruta y el
  endpoint habilitan al analista, y lo que ve es la cartera entera de la compañía. Lo que falta es
  el recorte a sus expedientes.

**No se van a hacer**, y no por falta de tiempo: las dos necesitan que el sistema tome un dato que
hoy no toma, así que son cambios al flujo y no al tablero.

- **Plazo de pago** (15 días desde que se acepta el siniestro). **No registramos en ningún lado
  cuándo se paga.** No es una métrica que falte: es un dato que no existe. Medirlo implica una
  acción nueva, su pantalla y el aviso al asegurado.
- **Siniestros Pendientes de Liquidación (reserva expuesta).** No tenemos "reserva" como concepto.
  Lo más cercano es lo reclamado en los expedientes abiertos, que es otra cosa y habría que aclarar
  en la pantalla que es una aproximación.

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
