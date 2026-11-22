# Guion de la presentación final — Arbiter

**Grupo 5303 · K5054.** 20 minutos de presentación y 10 de preguntas.

Objetivo de ensayo: **terminar en 18:30.** Quedan 1:30 de margen para fallas e imprevistos.

`[COMPLETAR]` marca un dato que falta. `[VALIDAR]` marca algo que hay que probar en un ensayo.

---

## 1 · Reparto de tiempos

| Minuto | Dura | Bloque | Quién muestra |
|---|---|---|---|
| 0:00 | 3:00 | Equipo, producto, alcance, mercado y modelo de negocio | Slides |
| 3:00 | 1:00 | Diagrama de contexto | Slide |
| 4:00 | 3:30 | Caso 1: Fast Track | Notebook analista y notebook referente, celular asegurado |
| 7:30 | 9:00 | Caso 2: sospecha de fraude | Las tres vistas |
| 16:30 | 1:00 | Lecciones aprendidas | Slide |
| 17:30 | 1:00 | Roadmap y cierre | Slide |
| 18:30 | 1:30 | Margen | — |

**Si van atrasados, se corta en este orden:**

1. La derivación a perito (se menciona y no se muestra).
2. El mail del caso 1.
3. La configuración de reglas del caso 2.

### Roles

| Rol | Tarea | Integrante |
|---|---|---|
| Narrador 1 | Marco y caso 1 | `[COMPLETAR]` |
| Narrador 2 | Caso 2 | `[COMPLETAR]` |
| Operador analista | Notebook en el proyector | `[COMPLETAR]` |
| Operador referente | Segunda notebook | `[COMPLETAR]` |
| Operador asegurado | Celular, con espejo de pantalla si se puede | `[COMPLETAR]` |

Cada integrante habla al menos en un bloque. Las lecciones aprendidas sirven para eso. Nadie de la cátedra toca la aplicación.

---

## 2 · El discurso

### Bloque 1 — El marco (0:00 a 3:00)

**Se muestra:** slide con número y nombre del equipo, fotos tipo carnet y nombres. Después, una slide de módulos.

> Buenas tardes. Somos el equipo 5303, `[COMPLETAR: nombre del equipo]`. Somos `[COMPLETAR: cantidad]` integrantes: `[COMPLETAR: nombres]`.
>
> Nuestro producto se llama Arbiter.
>
> Arrancamos con un problema concreto. Cuando a una persona le roban el celular, hace la denuncia y después no sabe qué pasa. Tiene que llamar para preguntar. Del otro lado, el analista lee cada expediente a mano y compara cada documento con la póliza. Y tiene que detectar el fraude a ojo.
>
> El objetivo de Arbiter es gestionar el ciclo de vida del siniestro con ayuda de IA. Reduce el tiempo de análisis y le da trazabilidad al asegurado. La decisión la toma siempre una persona. La Disposición 2/2023 de la SSN va en esa línea, y cada clasificación queda auditada.
>
> A efectos del proyecto, por plazo, esfuerzo y calidad, se asumió este alcance funcional: seis módulos y dos ramos.
>
> - **Denuncia:** el asegurado la carga en un wizard, con catálogos que dependen de su póliza.
> - **Análisis y clasificación:** reglas determinísticas primero y el modelo de lenguaje después.
> - **Expedientes:** estados, plazos legales, documentación, derivaciones y chat.
> - **Motor de reglas:** el referente de la aseguradora lo configura, sin desplegar.
> - **Reportes:** tablero y estadísticas.
> - **Usuarios:** tres roles, con Auth0.
>
> Los ramos son Celulares y Tecnología Portátil. Los elegimos porque nos permiten implementar el circuito entero y validar la propuesta. Automotor ya tiene soluciones en el mercado. Hogar y Accidentes Personales suman derivaciones e integraciones que nos alejaban del objetivo. El diseño ya separa los ramos por aseguradora, para poder sumar otros.
>
> Arbiter no reemplaza el sistema de pólizas ni ejecuta el pago. El pago queda en los sistemas de la compañía.
>
> Apuntamos a aseguradoras `[COMPLETAR: definición de "pequeñas" con fuente]`. Para esas compañías, una solución a medida es cara. Arbiter se integra con su base de datos y cada una configura sus propias reglas.
>
> El modelo de negocio es una suscripción que varía según la cantidad de siniestros procesados. `[COMPLETAR: confirmar con Agustín]`

### Bloque 2 — Diagrama de contexto (3:00 a 4:00)

**Se muestra:** diagrama con Arbiter al centro. Actores: asegurado, analista y referente. Sistemas: BD de la aseguradora, Auth0, SendGrid y el modelo de lenguaje.

> Este es el entorno de Arbiter. Tres perfiles usan el sistema: asegurado, analista y referente.
>
> Arbiter trae las pólizas de la base de la aseguradora y guarda una copia propia. Por eso funciona de forma autónoma y una clasificación se puede reproducir después.
>
> Cada aseguradora tiene sus datos en un esquema separado, dentro de la misma plataforma.
>
> Auth0 maneja el acceso y SendGrid envía los mails.
>
> Vamos a mostrar dos casos: el más simple y el más complejo. Cada perfil está en un dispositivo distinto.

### Bloque 3 — Caso 1: Fast Track (4:00 a 7:30)

**Datos:** robo de ayer. Expediente ya cargado. Hay uno por aseguradora:

- BBVA, Celulares: Sofía Montenegro, póliza `POL-CEL-2026-612`, Samsung Galaxy A16, reclama $ 429.999.
- Provincia, Tecnología Portátil: Tomás Ibarra, póliza `POL-TEC-2026-640`, HP Pavilion 14, reclama $ 1.299.999.

Se muestra uno. El otro queda de respaldo.

| Tiempo | Qué se hace | Qué se dice |
|---|---|---|
| 1:00 | **Referente:** Reglas → ramo del caso (Celulares o Tecnología Portátil) → cobertura Robo → solapa Fast Track | "Empezamos por la configuración. La referente define cuándo un siniestro es simple: qué documentos exige, cuánto puede reclamar sobre la suma asegurada, cuántos siniestros previos admite y si la póliza tiene que estar al día. Esto está en base de datos. Cambiarlo no requiere desplegar." |
| 0:30 | **Analista:** Bandeja → abre el expediente | "Sofía denunció el robo de su celular. Cumple todos los criterios. Por eso aparece como Fast Track." |
| 0:45 | **Analista:** solapa de reglas → Criterios de Fast Track | "Este resultado sale del motor de reglas. El modelo de lenguaje no participa y no puede devolver este valor. El analista ve cada criterio y su resultado." |
| 0:45 | **Analista:** Aprobar → hoja de liquidación → confirmar | "Fast Track agiliza, pero no automatiza. La analista aprueba. Arbiter calcula el monto a pagar según la cobertura. El pago lo ejecuta la aseguradora." |
| 0:30 | **Celular:** mail y estado del expediente | "Sofía recibe el aviso y ve el nuevo estado en su portal. No necesita llamar." |

### Bloque 4 — Caso 2: sospecha de fraude (7:30 a 16:30)

**Datos:** Valentín Aguirre, póliza `POL-CEL-2026-777`, Samsung Galaxy A56, hurto en un bar de San Telmo.

> Ahora, el caso más complejo. Intervienen los tres perfiles.

| Tiempo | Qué se hace | Qué se dice |
|---|---|---|
| 1:00 | **Referente:** Reglas → Hard Stop y Puntaje de riesgo | "Estas son las reglas que frenan un caso y los pesos de cada factor de fraude. Cada aseguradora define los suyos. El score orienta al analista y no decide nada." |
| 1:30 | **Celular:** Valentín abre el wizard. Elige póliza, hecho generador y bien. Adjunta acta y factura. | "Cada paso depende del anterior. El wizard ofrece solo lo que la póliza de Valentín contempla. Datos del asegurado y de la póliza ya vienen cargados. Valentín adjunta el acta y la factura." |
| 0:30 | **Celular:** enviar | "La clasificación es asincrónica. Valentín no espera: la denuncia queda registrada y él recibe el número de expediente." |
| 1:00 | **Analista:** abre el expediente con Falta documentación → elige qué pedir | "Este caso no entra en Fast Track, y la agenda documental exige más documentos. Arbiter sugiere los faltantes según la banda de riesgo. La analista decide cuáles pedir." |
| 1:00 | **Analista y celular:** chat → Valentín responde y sube la constancia de última conexión | "Además tienen un chat dentro del expediente. El asegurado puede consultar y la analista puede pedirle algo directamente: 'Cargame esto para seguir'. Mientras espera documentación, el plazo legal queda congelado, porque depende del asegurado." |
| 0:15 | **Analista:** muestra el expediente en clasificación → cambia al ya clasificado | "Cargar la constancia dispara la reclasificación. Para no esperar, seguimos con un expediente que ya pasó por este punto." |
| 1:15 | **Analista:** recomendación, solapa de análisis → Razones | "Acá trabaja la IA. El prompt recibe campos estructurados, el relato, los documentos y las reglas de la aseguradora. La recomendación no es vinculante y explica sus motivos. El relato dice que el celular se perdió en un bar. Pero la constancia lo ubica toda la noche bajo la antena del domicilio de Valentín. Ninguna regla cruza esos datos. Lo detecta el modelo." |
| 1:00 | **Analista:** Score de fraude → desglose → imagen | "El score se muestra desglosado por factor. La foto se compara por embeddings contra las de otras denuncias. Esa comparación no usa el modelo de lenguaje. `[VALIDAR: mencionar Google Vision solo si queda encendido]`" |
| 0:30 | **Analista:** solapa de reglas → Para revisar y Reglas evaluadas | "Las reglas evalúan condiciones objetivas y el modelo aporta la lectura interpretativa. Todo queda en un registro inmutable: resultado, factores, decisión y fecha." |
| 0:30 | **Analista:** Derivar a… → peritaje | "La analista no resuelve con una sospecha: deriva a un perito. El plazo vuelve a congelarse, porque ahora depende de un tercero." |
| 0:30 | **Celular:** estado del expediente, junto a la vista del analista | "Comparen las dos pantallas. Valentín ve el estado del trámite. No ve la clasificación, el score ni la sospecha." |

### Bloque 5 — Lecciones aprendidas (16:30 a 17:30)

**Hablan al menos dos integrantes que no hayan narrado.** Texto propuesto a partir del repositorio: reemplácenlo por lo que vivieron.

> `[Integrante]`: Nuestro mayor riesgo técnico fue el modelo local. Ocupa 6 GB y una versión tardaba 27 minutos por documento sin devolver texto. Lo mitigamos con otra variante del modelo y dejamos el proveedor detrás de una interfaz.
>
> `[Integrante]`: También aprendimos del dominio. Empezamos con un solo juego de reglas por ramo, y al relevar vimos que los criterios son por cobertura. Si volviéramos a empezar, validaríamos antes el dominio con casos de prueba reales.

### Bloque 6 — Roadmap y cierre (17:30 a 18:30)

**Se muestra:** slide de roadmap, estilo SAP, con tres releases. `[COMPLETAR: fechas aproximadas]`

> Arbiter tiene un roadmap de tres releases.
>
> - **Release 1:** alta de ramos desde la pantalla, empezando por Hogar y Accidentes Personales.
> - **Release 2:** historial crediticio del asegurado como factor de riesgo.
> - **Release 3:** análisis de los documentos propios de la aseguradora para precargar sus reglas.
>
> Arbiter no reemplaza al analista. Le da la información ordenada para que decida mejor y más rápido. Y al asegurado le da algo que hoy no tiene: saber en qué está su trámite.
>
> Muchas gracias. Quedamos a disposición para sus preguntas.

---

## 3 · Antes de ensayar

### Decisiones tomadas en este guion (se pueden cambiar)

1. **Enfoque:** los roles de la aseguradora llevan la demo. El asegurado aparece en el caso 2 y al final del caso 1.
2. **Reglas a mostrar:** Fast Track en el caso 1. Hard Stop y Puntaje de riesgo en el caso 2. Nada más.
3. **Liquidación:** la idea inicial dice "no llegamos a liquidación" y también "se liquida el monto". Arbiter calcula el monto, y el referente autoriza si supera la atribución del analista. Por eso el guion dice que Arbiter calcula y que **el pago** queda afuera.
4. **Sin "thinking trace":** esa pantalla no existe en el frontend. Se muestran Razones, Score de fraude, Para revisar antes de resolver y Reglas evaluadas.
5. **Caso "fraudulento":** una denuncia con documentos adulterados que cumple el gate da Fast Track, y el LLM no corre (`docs/postman/test-docs/README.md` §9). Por eso el caso 2 usa `hurto-bar`, que no entra en Fast Track.

### Datos de la demo

- [ ] Caso 1: usar los sets de Sofía (BBVA) y Tomás (Provincia). Caso 2: usar el de Valentín. No usar los de Martina: tiene demasiados expedientes, ya no da Fast Track, y además es integrante del equipo.
- [ ] Una sola vez: correr la migración de Sofía y Tomás, el alta masiva de asegurados en cada aseguradora y aceptar las dos invitaciones (`docs/postman/test-docs/README.md` §12).
- [ ] Regenerar los fixtures la víspera. Las fechas son relativas y los plazos de 72 y 96 horas los vencen:
  ```bash
  node docs/postman/test-docs/generar-fixtures.js --demo-bbva
  ```
  ```bash
  node docs/postman/test-docs/generar-fixtures-tecnologia.js --demo-provincia
  ```
  ```bash
  node docs/postman/test-docs/generar-fixtures.js --nuevos
  ```
- [ ] Correr `scripts/reset-asegurados-de-prueba.sql` antes de cargar. Borra los expedientes de Sofía, Tomás, Valentín y Camila.
- [ ] Cargar el robo de Sofía y el de Tomás desde el portal, y confirmar que los dos den Fast Track.

### Lo que hay que probar `[VALIDAR]`

Estos puntos salen de leer la documentación y el código. **No corrí nada.** La documentación de casos de prueba es de septiembre.

- [ ] **El ida y vuelta:** `hurto-bar` con solo acta y factura da `FALTA_DOCUMENTACION`, y la constancia se puede pedir.
- [ ] **La señal de la antena:** el modelo la marca en Razones. Depende de la salida del modelo, así que hay que repetir la prueba varias veces y **dejar cocinado el expediente en el que salió bien.**
- [ ] **La imagen reutilizada:** cargar antes la misma foto en otro expediente y confirmar que `image_reuse` sume puntaje. `GOOGLE_VISION_ENABLED` viene apagado.
- [ ] **El peso de los factores:** en septiembre `document_inconsistency` no tenía peso en ninguna aseguradora. Revisar `factor_weight`.
- [ ] **El hermano cocinado:** el expediente ya clasificado debe tener los mismos datos que el que se carga en vivo. Si es del mismo asegurado, cada carga cuenta como siniestro previo del siguiente.
- [ ] **El arranque:** despertar los módulos de Railway 10 minutos antes, porque duermen.

### Plan B

- Video de cada caso completo, grabado en el último ensayo.
- Datos móviles propios, si falla la red del laboratorio.
- Sesiones iniciadas en todos los dispositivos antes de entrar.

---

## 4 · Preguntas (10 minutos)

Asignar una persona a cada pregunta.

| Pregunta | Base de la respuesta | Responde |
|---|---|---|
| ¿Quién fue el líder? ¿Qué problemas hubo? | `[COMPLETAR]` | `[COMPLETAR]` |
| ¿Qué sí y qué no volverían a hacer? | `[COMPLETAR]` | `[COMPLETAR]` |
| ¿Cuál fue el momento más crítico? | `[COMPLETAR]` | `[COMPLETAR]` |
| ¿Qué fue lo más difícil a nivel técnico? | Modelo local: memoria, tiempos y razonamiento que no se podía apagar | `[COMPLETAR]` |
| ¿Por qué solo Celulares y Tecnología Portátil? | Validan el circuito entero. Automotor ya tiene soluciones. Hogar y Accidentes Personales suman derivaciones e integraciones. | `[COMPLETAR]` |
| ¿Usan Ollama en esta demo? | Ollama es el default de la arquitectura. Railway corre con Gemini porque el entorno no sostiene el modelo local. El cambio es de configuración, detrás de una interfaz. **Decirlo sin rodeos.** | `[COMPLETAR]` |
| ¿La IA puede rechazar sola? | No. Toda clasificación requiere la decisión de un analista, también en Fast Track. | `[COMPLETAR]` |
| ¿Qué pasa si el modelo se equivoca? | Es una recomendación con motivos. El analista decide y queda auditado. | `[COMPLETAR]` |
| ¿Cómo se suma un ramo nuevo? | Hoy no se crea desde la pantalla. El diseño lo soporta por configuración; falta el alta y otras configuraciones (roadmap). | `[COMPLETAR]` |
| Una función no contemplada | "Eso está fuera del alcance del presente proyecto." Con una sonrisa. | Quien esté narrando |
