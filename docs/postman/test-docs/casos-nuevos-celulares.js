// Two fresh Celulares cases for Valentín Aguirre (perfiles.js, `nuevos`): new stories, places and
// numbers, so none of their documents repeats a line of the regular sets.
//
//   hurto-bar/        hurto in a San Telmo bar, last night 23:10   → goes to the LLM
//   robo-motochorros/ robo at a bus stop in Palermo, last night 21:50 → Fast Track if filed first
//
// The hurto carries one signal and nothing else: the carrier's log places the phone, after the
// theft, under the cell that covers the insured's home. It's an interpretive reading — nothing in
// the rule engine compares a cell against an address — so it only surfaces in the LLM's factors.
// The claimed amount (589.999 over Hurto's 650.000, ratio 0.91) keeps it out of Fast Track
// (maxClaimedAmountRatio 0.5) so the LLM always reads it.
//
// The robo is coherent end to end: 589.999 over Robo's 1.300.000 (0.45) passes the ratio, and with
// no prior claims it fast-tracks. Filed after the hurto, maxPriorClaims (0) fails and it goes to
// the LLM too, now with a prior claim of the same day.

const { plus } = require('./lib-pdf');

// One invoice of their own: the regular sets share 0003-00041827, and a document number repeated
// across insureds is the kind of thing a reviewer would rightly flag.
const PURCHASE = {
  date: '18/12/2025',
  invoice: '0004-00052611',
  unitPrice: '589.999,00',
  net: '487.602,48',
  vat: '102.396,52',
  cae: '75351187204419',
  caeDue: '28/12/2025',
};

/** No document can be dated after the run that generates it. */
const notAfter = (when, now) => new Date(Math.min(when.getTime(), now.getTime() - 5 * 60 * 1000));

function buildNuevos({ G, ayerA, NOW }) {
  const shared = { overrides: { purchase_proof: { purchase: PURCHASE } } };

  // ── Hurto en un bar ─────────────────────────────────────────────────────────
  // Hurto, not robo: the phone was on the table and nobody touched him (art. 162). He notices it
  // at once, searches with the staff, goes home, and blocks it and reports it the next morning.
  const hurtoEvent = ayerA(23, 10);
  const hurto = {
    ...shared,
    folder: 'hurto-bar',
    claimCause: 'Hurto',
    documents: ['police_report', 'purchase_proof', 'imei_deregistration', 'last_connection'],
    event: hurtoEvent,
    claimedAmount: 589999,
    eventLocation: 'Defensa 990, interior de un bar',
    locality: 'San Telmo',
    province: 'Ciudad Autónoma de Buenos Aires',
    description:
      'Estaba en un bar de Defensa al 900 con amigos. Dejé el celular sobre la mesa y fui al baño. '
      + 'Cuando volví ya no estaba y nadie vio quién lo agarró. Lo buscamos con el mozo y no apareció. '
      + 'Volví a mi casa en taxi. A la mañana pedí el bloqueo a la compañía e hice la denuncia en la comisaría.',
    police: {
      at: notAfter(plus(hurtoEvent, 685), NOW),   // next morning, 10:35
      number: '1E-2026-009873',
      folio: '57',
      station: 'Comisaría Vecinal 1-E — San Telmo',
      stationAddress: 'Av. San Juan 950, Ciudad Autónoma de Buenos Aires — Tel. (011) 4300-2150',
      officer: 'Of. Ayte. Matías E. Correa (Leg. 44.318)',
      officerShort: 'Of. Ayte. M. E. Correa',
      caratula: 'HURTO (art. 162 del Código Penal de la Nación)',
      fiscalia: 'N° 4',
      place: [
        'Local gastronómico sito en Defensa 990,',
        '             barrio de San Telmo, C.A.B.A. — interior del local, salón principal.',
      ],
      relato: (hora) => [
        `Que siendo aproximadamente las ${hora} horas del día de ayer, ${G.el} denunciante se`,
        'encontraba en el salón principal de un bar de Defensa al 900 junto a tres amigos,',
        'sentados a una mesa próxima a la puerta. Que dejó su teléfono celular sobre la mesa y',
        'se dirigió al sanitario del local por un lapso de cinco minutos aproximadamente. Que',
        'al regresar advirtió la falta del aparato. Que sus acompañantes manifestaron no haber',
        'visto a nadie tomarlo. Que junto al personal del local revisó el salón sin resultado.',
        `Que no fue abordad${G.a} ni amenazad${G.a}, y no puede sindicar autor ni aportar`,
        'descripción alguna. Que el local cuenta con cámaras de seguridad internas.',
      ],
      evidence: [
        '•  Se libró oficio al local comercial a fin de requerir el resguardo de las imágenes',
        '   de sus cámaras internas correspondientes a la fecha y franja horaria del hecho.',
      ],
    },
    block: {
      motivo: 'Hurto del equipo en local comercial',
      requested: plus(hurtoEvent, 610),           // 09:20, before going to the police station
      effective: plus(hurtoEvent, 628),
      issued: notAfter(plus(hurtoEvent, 760), NOW),
      number: 'BLQ-2026-0913377',
      gestion: '6142085',
      canal: 'Línea de atención telefónica',
      antesDeLaDenuncia: true,
    },
    lastConnection: {
      number: 'REG-2026-0913378',
      at: plus(hurtoEvent, 628, 2),               // the block is what takes it off the network
      site: 'PAL-0219 — Av. Scalabrini Ortiz 1900, C.A.B.A.',
      lac: '21388',
      cid: '57412',
      cobertura: 'Av. Scalabrini Ortiz entre Cabrera y Gorriti, C.A.B.A.',
      // The signal: after 23:10 the phone leaves San Telmo and spends the night under the cell
      // that covers Av. Scalabrini Ortiz 1920, the insured's own address.
      registros: [
        [-38, 14, 'Actualización de ubicación', 'STE-0127 — Defensa 980'],
        [-4, 51, 'Datos móviles (sesión)     ', 'STE-0127 — Defensa 980'],
        [52, 9, 'Actualización de ubicación', 'PAL-0219 — Scalabrini Ortiz 1900'],
        [217, 33, 'Datos móviles (sesión)     ', 'PAL-0219 — Scalabrini Ortiz 1900'],
        [628, 2, 'Desconexión de red        ', 'PAL-0219 — Scalabrini Ortiz 1900'],
      ],
    },
    expected: {
      perfil: 'nuevos',
      cambia: 'hurto en un bar de San Telmo, anoche — el equipo pasa la noche bajo la antena del domicilio del asegurado',
      antes: 'correr scripts/reset-asegurados-de-prueba.sql si Valentín ya tiene expedientes: este caso va primero',
      seDetectaEn: [
        'rule_result: Fast Track en FAIL por el ratio (589.999 sobre 650.000, 0,91; máximo 0,5)',
        'factores del LLM: el último registro de red está en Av. Scalabrini Ortiz 1900, el domicilio declarado, horas después del hurto',
        'factores del LLM: ninguno dice "el modelo", "la IA" ni "el sistema" (classification-v9)',
      ],
      resultadoEsperado: 'LLM_NO_RECOMIENDA_APROBAR o LLM_SOLICITA_REVISION_MANUAL',
      notas: [
        'La factura, la denuncia y la baja de IMEI son coherentes entre sí: la única señal está en la constancia de último registro.',
      ],
    },
  };

  // ── Robo con motochorros ────────────────────────────────────────────────────
  // Robo: the passenger gets off the motorcycle and takes the phone from his hand under verbal
  // intimidation (art. 164, no weapon). He blocks it from a friend's phone and reports it that night.
  const roboEvent = ayerA(21, 50);
  const robo = {
    ...shared,
    folder: 'robo-motochorros',
    claimCause: 'Robo en vía pública',
    documents: ['police_report', 'purchase_proof', 'imei_deregistration', 'last_connection'],
    event: roboEvent,
    claimedAmount: 589999,
    eventLocation: 'Av. Córdoba 4850, parada de colectivo',
    locality: 'Palermo',
    province: 'Ciudad Autónoma de Buenos Aires',
    description:
      'Esperaba el colectivo en Av. Córdoba al 4800 con el celular en la mano. Dos personas en moto '
      + 'frenaron en la parada. El acompañante se bajó, me dijo que le diera el teléfono y me lo sacó '
      + 'de la mano. Se fueron por Av. Córdoba hacia Juan B. Justo. Pedí el bloqueo del IMEI por '
      + 'teléfono y después hice la denuncia en la comisaría.',
    police: {
      at: notAfter(plus(roboEvent, 75), NOW),    // 23:05, same night
      number: '14B-2026-016204',
      folio: '311',
      station: 'Comisaría Vecinal 14-B — Palermo',
      stationAddress: 'Av. Santa Fe 4250, Ciudad Autónoma de Buenos Aires — Tel. (011) 4776-3010',
      officer: 'Of. Mayor Lucía B. Ferraro (Leg. 37.902)',
      officerShort: 'Of. My. L. B. Ferraro',
      caratula: 'ROBO (art. 164 del Código Penal de la Nación)',
      fiscalia: 'N° 12',
      place: [
        'Av. Córdoba al 4850, parada de colectivo frente a la numeración,',
        '             barrio de Palermo, C.A.B.A. — vía pública.',
      ],
      relato: (hora) => [
        `Que siendo aproximadamente las ${hora} horas del día de la fecha, ${G.el} denunciante se`,
        'encontraba en la parada de colectivo de Av. Córdoba al 4850 con su teléfono celular en',
        'la mano. Que se detuvo frente a la parada una motocicleta de color negro con dos',
        'ocupantes. Que el acompañante descendió, le exigió en forma verbal que le entregara el',
        `teléfono y se lo quitó de la mano. Que ${G.el} denunciante no exhibió resistencia. Que`,
        'ambos se dieron a la fuga por Av. Córdoba en dirección a Av. Juan B. Justo. Que no',
        `observó armas y no sufrió lesiones. Que no pudo registrar el dominio del rodado.`,
        'Que sobre la avenida existen cámaras del Sistema de Monitoreo Público de la Ciudad.',
      ],
      camaras: true,
    },
    block: {
      motivo: 'Robo del equipo en la vía pública',
      requested: plus(roboEvent, 25),             // 22:15, from a friend's phone
      effective: plus(roboEvent, 41),
      number: 'BLQ-2026-0913104',
      gestion: '6141877',
      canal: 'Línea de atención telefónica',
      antesDeLaDenuncia: true,
    },
    lastConnection: {
      number: 'REG-2026-0913105',
      at: plus(roboEvent, 11, 3),                 // switched off on the way out
      site: 'PAL-0311 — Av. Juan B. Justo 1500, C.A.B.A.',
      lac: '21391',
      cid: '57630',
      cobertura: 'Av. Juan B. Justo entre Córdoba y Cabrera, C.A.B.A.',
      registros: [
        [-21, 7, 'Datos móviles (sesión)     ', 'PAL-0305 — Córdoba 4800'],
        [2, 48, 'Actualización de ubicación', 'PAL-0305 — Córdoba 4800'],
        [9, 15, 'Actualización de ubicación', 'PAL-0311 — Juan B. Justo 1500'],
        [11, 3, 'Desconexión de red        ', 'PAL-0311 — Juan B. Justo 1500'],
      ],
    },
    expected: {
      perfil: 'nuevos',
      cambia: 'robo en una parada de colectivo de Palermo, anoche — caso limpio',
      antes: 'cargado solo (historial en cero) da Fast Track; cargado después del hurto, va al LLM',
      seDetectaEn: [
        'rule_result: criterios de FAST_TRACK en PASS (ratio 0,45; 0 siniestros previos)',
        'si va después del hurto: maxPriorClaims en FAIL (1 previo) y factores del LLM sin "el modelo"',
      ],
      resultadoEsperado: 'FAST_TRACK, o LLM_RECOMIENDA_APROBAR si se carga después del hurto',
      notas: [],
    },
  };

  return [hurto, robo];
}

module.exports = { buildNuevos };
