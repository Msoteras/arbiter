# Guion de la demo final — Arbiter

18/11/2026 · Aylén · actualizado el 20/11/2026

La presentación dura 20 minutos, más 10 de preguntas. El guion dura 17:45 y deja 2:15 de margen. Todo corre en BBVA, con cuatro perfiles de Chrome. Cada paso de los casos tiene tres partes:

- **Presenta:** la narradora abre cada caso con su contexto.
- **Hacer:** qué se toca en la pantalla.
- **Dice:** lo dice quien opera el rol (analista, asegurado o referente).
- **Impacto:** lo dice la narradora, al cerrar el paso.

## Antes de la práctica

- [ ] Cerrar este guion: repartir los narradores y los operadores, y completar los datos pendientes.
- [x] Deshabilitar el sleeping de todos los servicios de Railway. Hecho el 21/11/2026. Volver a prenderlo después de la demo, salvo en `cases-service` y Postgres.
- [ ] Pasada final a la BD de BBVA: ver "Alta en BBVA", en Preparación.
- [ ] Probar la carga de denuncias de punta a punta. El 20/11 se probó con Camila: el wizard funcionó y dio Fast Track en segundos. Falta con Sofía, Tomás, la semilla de Roman y la rotura de Valentín, todos en BBVA.
- [ ] Hacer los tres carteles de rol: Referente, Analista y Asegurado.

## Perfiles

| Perfil | Quién | Caso 1 | Caso 2 |
| --- | --- | --- | --- |
| Referente | Flor | — | Paso 6 |
| Analista | Martina | Pasos 1 a 4 | Pasos 2, 3, 5 y 7 |
| Sofía | Federico | Paso 5 | — |
| Valentín | Federico | — | Pasos 1 y 4 |

La apertura y las conclusiones las dice Flor. La presentación de cada caso y el impacto los dicen Valentina y Aylén. Para cambiar de ventana: Alt+Tab en Windows, Cmd y acento grave en Mac. Roman no tiene sesión: su caso se siembra la víspera.

## Apertura (2:00)

1. **Portada y diapo 1** (0:15). "Buenas tardes. Somos Arbiter. Gestionamos el ciclo de vida de los siniestros, de la denuncia a la decisión."
2. **Problemática** (0:45). "Preguntamos a profesionales del sector y a asegurados dónde se traba un siniestro. En la aseguradora, el 74 % señala el pedido de documentación faltante y el 48 %, la detección de fraude. Del lado del asegurado, 6 de cada 10 tuvieron que llamar para saber cómo iba su trámite. Y según Invoice Cloud, el 83 % cambiaría de aseguradora después de una mala experiencia."
3. **Diapo 3 · ¿Cómo funciona?** (0:45). "El asegurado hace la denuncia. Arbiter la procesa y evalúa las reglas de la aseguradora. Si el caso es simple, va por Fast Track. Si no, Arbiter arma una recomendación con sus razones. La decisión es siempre del analista. Lo vemos en dos casos de BBVA: uno simple y uno complejo."
4. **Diapo 4 · En vivo** (0:15). "Antes de empezar, cómo nos organizamos. Cada persona con un cartel es un rol: la referente de la aseguradora, la analista y los asegurados. En paralelo, Valentina y Aylén cuentan qué gana el negocio en cada paso. Pasemos a Arbiter."

En el deck: sacar la diapo de la pregunta y la 2. La problemática va después de la 1.

## Caso 1 · Fast Track (5:00)

**Presenta la narradora:** "El primer caso es simple. Sofía Montenegro, asegurada de BBVA, denunció ayer el robo de su celular a la salida del subte. Adjuntó la denuncia policial y la factura."

1. **Analista · Bandeja** (1:00)
    - **Hacer:** abrir la Bandeja. Mostrar la columna de clasificación y abrir el expediente de Sofía.
    - **Dice la analista:** "Esta es mi Bandeja. Cada expediente llega ya clasificado, así que sé por dónde empezar sin abrir uno por uno. Hay casos Fast Track, casos con documentación faltante y casos con una recomendación del motor de análisis. El de Sofía llegó como Fast Track."
    - **Impacto:** "Los casos simples dejan de pasar por la revisión manual. Hoy, 8 de cada 10 profesionales dicen que más de la mitad del proceso es manual."
2. **Analista · Criterios de Fast Track** (1:15)
    - **Hacer:** abrir el panel Criterios de Fast Track del expediente.
    - **Dice la analista:** "Voy a aprobar el caso. Antes, reviso por qué es Fast Track. Esta clasificación no la decide el motor de análisis: sale de reglas fijas. Cada criterio muestra su resultado. Sofía presentó la documentación exigida. Reclama el 48 % de la suma asegurada, por debajo del tope. Es su primer siniestro. Y la póliza está al día."
    - **Impacto:** "Cada Fast Track tiene sus fundamentos listos para una auditoría."
3. **Analista · Reglas** (1:00)
    - **Hacer:** ir a Reglas → Celulares → Robo → solapa Fast Track.
    - **Dice la analista:** "Estas son las reglas que determinan la vía Fast Track. Las configura la referente de la aseguradora, por ramo y por cobertura. Acá se ven los documentos que exige, el tope de la suma asegurada, los siniestros previos que admite y si la póliza tiene que estar al día. Yo solo las consulto."
    - **Impacto:** "Cada aseguradora define sus reglas, sin desarrollo ni despliegue."
4. **Analista · Aprobar** (1:00)
    - **Hacer:** volver al expediente. Aprobar → revisar la liquidación → Confirmar.
    - **Dice la analista:** "Fast Track agiliza, pero no automatiza: la decisión es mía. Apruebo. Arbiter calcula el monto según la cobertura de la póliza. Reviso la liquidación y confirmo. El pago lo hace la aseguradora en sus sistemas."
    - **Impacto:** "Lo simple se resuelve en el día, y siempre decide una persona."
5. **Sofía · Portal** (0:45)
    - **Hacer:** pasar al perfil Sofía y abrir su siniestro.
    - **Dice Sofía:** "Entro al portal. Mi siniestro pasó por Denunciado, En trámite y Terminado, y ahora dice Aprobado. También me llegó el aviso por mail, así que no tuve que llamar."
    - **Impacto:** "La novedad llega al asegurado sin que la pida. Hoy, 6 de cada 10 asegurados tienen que llamar para saber cómo va su trámite."

Respaldo: el expediente de Tomás Ibarra (BBVA · Tecnología Portátil · Robo). En el paso 3 se abre Tecnología Portátil → Robo. Su portal va en otra pestaña del perfil Sofía.

## Caso 2 · Rotura de notebook (10:00)

**Presenta la narradora:** "El segundo caso es complejo. Valentín Aguirre, asegurado de BBVA, denuncia que su notebook se cayó de la mesa y se rompió la pantalla. Lo vamos a cargar en vivo."

1. **Valentín · Wizard** (2:00)
    - **Hacer:** en el perfil Valentín, Nueva denuncia → póliza de BBVA → Rotura accidental → bien. Pegar los datos, adjuntar la factura y el presupuesto, y enviar.
    - **Dice Valentín:** "Tengo pólizas en BBVA y en Provincia, con un solo usuario. Elijo la póliza de BBVA. El wizard me ofrece solo lo que mi póliza contempla: primero el hecho, después el bien asegurado. Cargo cuándo y dónde pasó, cuento qué pasó y adjunto la factura y el presupuesto del service. Envío y recibo mi número de siniestro. No tengo que esperar el análisis."
    - **Impacto:** "La denuncia entra completa por un solo canal, sin mails ni planillas. Hoy, 1 de cada 4 asegurados no sabe qué documentación presentar."
2. **Analista · Bandeja** (1:00)
    - **Hacer:** en el perfil Analista, abrir el expediente de Valentín.
    - **Dice la analista:** "Me llega el expediente de Valentín. Este caso no es Fast Track: reclama el 74 % de la suma asegurada, más que el tope. Arbiter lo clasifica como Falta documentación: para su banda de riesgo, la agenda pide más documentos."
    - **Impacto:** "La clasificación llega sola, apenas entra la denuncia."
3. **Analista · Pedir documentación** (1:15)
    - **Hacer:** Pedir documentación → marcar Foto del bien → enviar.
    - **Dice la analista:** "Arbiter me sugiere qué documentación pedir según la banda de riesgo del caso. La sugerencia no es obligatoria: yo elijo qué se le pide al asegurado. Pido la foto del bien. Mientras Valentín no responda, el plazo legal queda congelado, porque la espera depende de él."
    - **Impacto:** "Se pide solo lo necesario según el riesgo. Es el principal cuello de botella del sector: lo señalan 3 de cada 4 profesionales."
4. **Valentín · Portal** (1:00)
    - **Hacer:** en el perfil Valentín, abrir el siniestro y subir `foto_notebook_rota.jpg`.
    - **Dice Valentín:** "En el portal me aparece «Necesitamos algo de tu parte», con el documento que me piden. Subo la foto del equipo dañado."
    - **Impacto:** "El pedido y la respuesta quedan en el expediente. Con la foto, el caso pasa al motor de análisis."
5. **Analista · Arbiter** (0:45)
    - **Hacer:** en el perfil Analista, abrir la solapa Arbiter.
    - **Dice la analista:** "En la solapa Arbiter veo qué está analizando el motor: los expedientes en cola, en análisis y los clasificados en la semana. El de Valentín está en análisis. Mientras termina, la referente nos muestra cómo se configura."
    - **Impacto:** "El análisis corre en segundo plano. Nadie espera frente a la pantalla."
6. **Referente · Reglas** (2:00)
    - **Hacer:** en el perfil Referente, Reglas → Aseguradora → Puntaje de riesgo. Después, Tecnología Portátil → Agenda documental.
    - **Dice la referente:** "El puntaje de riesgo suma señales en tres grupos. Primero, los datos del siniestro y del asegurado: el monto, los siniestros previos, la póliza al día, el tiempo entre la compra y la denuncia, y los antecedentes de fraude. Después, los documentos y las imágenes. Por último, el análisis del modelo. Yo defino cuánto pesa cada señal y dónde empieza cada banda: Bajo, Medio, Alto y Crítico. En la agenda documental del ramo, defino qué documentos se piden en cada banda."
    - **Impacto:** "Cada aseguradora configura sus bandas al incorporarse a Arbiter, sin desarrollo."
7. **Analista · Razones** (2:00)
    - **Hacer:** en el perfil Analista, abrir el expediente. Mostrar el puntaje de riesgo y después Recomendación → Razones.
    - **Dice la analista:** "El análisis terminó. Esto reviso antes de decidir. Primero, el puntaje de riesgo: la foto ya la usó otro asegurado de BBVA, en otra denuncia. Después, las razones del motor de análisis. Leyó la factura y el presupuesto, y miró la foto. Los documentos hablan de una Lenovo de 2025, pero la foto muestra una Acer con etiquetas de Windows 7. La recomendación no es vinculante: la decisión sigue siendo mía."
    - **Impacto:** "La detección de fraude deja de depender del ojo del analista. La analista sabe dónde mirar antes de pagar."

Si el análisis no termina en el paso 6, se abre el respaldo que se prepara la víspera. El paso 7 necesita la semilla de Roman: sin ella, el puntaje no marca la foto reutilizada. Antes de cada ensayo, confirmar que el paso 2 muestre 74 %.

## Cierre (0:45)

1. **Diapo 6 · Conclusiones** (0:45). Saltear la diapo 5. "Para cerrar: menos esperas para el asegurado y más información para el analista. El expediente llega completo. Cada clasificación muestra los factores que la sostienen. Y cada clasificación y cada decisión quedan registradas."
2. **Diapo 7 · Preguntas.** "Muchas gracias. Quedamos a disposición para sus preguntas."

## Datos de carga

Los valores para pegar en el wizard están en [Datos de prueba de la demo](https://claude.ai/artifact/BBNYeLF5UuC91fy8FKBw73).

## Preparación

**Alta en BBVA, una sola vez**

1. Correr el alta masiva de asegurados de BBVA. Sofía, Tomás y Roman aceptan la invitación.
2. Cargar en la Bandeja de BBVA uno o dos expedientes de cada clasificación, con asegurados que no sean los de la demo. Así el reset no los borra.

**La víspera**

1. Generar los documentos. Los plazos de denuncia son de 72 horas en Celulares y de 96 en Tecnología Portátil:
    - `node docs/postman/test-docs/generar-fixtures.js --demo-bbva`
    - `node docs/postman/test-docs/generar-fixtures-tecnologia.js --demo-provincia`
    - `node docs/postman/test-docs/generar-fixtures-tecnologia.js --demo-semilla`
    - `node docs/postman/test-docs/generar-fixtures-tecnologia.js --demo-caso2`
2. Correr `scripts/reset-asegurados-de-prueba.sql`. Borra los expedientes de Sofía, Tomás y Valentín. No borra la semilla de Roman ni los expedientes de relleno.
3. Cargar los robos de Sofía y de Tomás desde el portal. Confirmar en la Bandeja que los dos dan Fast Track y que el de Sofía se ve sin hacer scroll.
4. Solo la primera vez, sembrar la foto: Roman denuncia su rotura en BBVA, la analista le pide la foto y Roman sube `foto_notebook_rota.jpg`. Esperar la reclasificación: recién ahí queda guardada la foto para comparar. La comparación es dentro de cada aseguradora: si Roman denuncia en otra, el caso 2 no encuentra la foto.
5. Preparar el respaldo del caso 2: Valentín denuncia su rotura, la analista le pide la foto y Valentín la sube. Confirmar que Razones menciona la notebook de la foto. Si no, repetir.
6. Verificar si 429.999 (Sofía) o 1.299.999 (Tomás) superan la atribución del analista en BBVA. Si la superan, el paso 4 del caso 1 suma la autorización de la referente.

**Después de cada ensayo**

Repetir "La víspera" desde el paso 2. Aprobar o derivar consume los expedientes de Sofía y de Valentín.

**El día, 15 minutos antes**

1. Recorrer todas las pantallas de la demo una vez. Despierta los módulos de Railway y carga el cache.
2. Iniciar sesión en los cuatro perfiles. La sesión dura 60 minutos desde el login y no se renueva, así que alcanza. Un F5 no la corta; cerrar la pestaña, sí.
3. Dejar cada perfil en la pantalla con la que arranca: la referente en Reglas, la analista en la Bandeja, Sofía en su Gmail sin otros mails a la vista y Valentín en el inicio del portal.
4. Tener a mano los PDF y la foto de Valentín, y su relato copiado para pegarlo.

## Si vamos atrasados

Se corta en este orden. Ningún corte saca un rol de un caso.

1. El Gmail del caso 1: Sofía muestra solo el portal. Ahorra 0:15.
2. El wizard del caso 2: precargado, solo enviar. Ahorra 1:00.
3. La agenda documental del caso 2 (paso 6): se muestra solo el puntaje de riesgo. Ahorra 0:30.
4. La espera del caso 2: se abre el respaldo, ya analizado. Ahorra lo que tarde el análisis.

## Datos a completar

- [ ] Qué narradora toma cada caso.
- [ ] Martina: confirmar que BBVA vende cobertura de notebooks. El relevamiento solo verificó el seguro de celular.
