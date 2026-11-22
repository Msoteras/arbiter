// The final presentation's Fast Track case in BBVA (perfiles.js, `demoBbva`): Sofía Montenegro's
// Samsung Galaxy A16 is snatched from her hand at a subway exit yesterday evening. Filed with the two
// documents the wizard asks first: the police report and the invoice.
//
//   demo/bbva/celulares/robo/
//
// Why it fast-tracks (BBVA's FAST_TRACK rule on the Robo coverage):
//   · police_report + purchase_proof are the required documents, and both are text PDFs.
//   · 429.999 over the basic plan's 900.000 (0.48): under the 0.5 claimed amount ratio.
//   · No prior claims, policy paid up, in force since 01/07/2026 (past the 30-day waiting period).
//   · Police report 1 h 15 min after the event: well inside the 72 h police deadline.
//   · Reported the day after: inside the 72 h report deadline. Load it within 2 days of generating.
//   · The report narrates a snatch (a tirón): it reads as Robo, so CLAIM_CAUSE_MATCH stays quiet.
//
// Market-like values: the A16 128 GB is the best-selling Samsung in the country, bought in June.
// Its own invoice, IMEI and police record: nothing repeats the other sets.

const { cuit, plus, hm } = require('./lib-pdf');

const PURCHASE = {
  date: '14/06/2026',
  invoice: '0006-00027493',
  unitPrice: '429.999,00',
  total: '429.999,00',
  net: '355.371,07',
  vat: '74.627,93',
  cae: '76243918507126',
  caeDue: '24/06/2026',
  payment: 'Tarjeta de Crédito',
  created: "20260614183200-03'00'",
};

// The documents use the real-looking layouts of documentos-demo.js: these are the fields they add.
const RETAILER = {
  name: 'Movilnet',
  legalName: 'MOVILNET ELECTRONICA S.R.L.',
  address: 'Av. Cabildo 2230 - Ciudad de Buenos Aires',
  cuit: cuit('30', '71590362'),
  iibb: '901-590362-4',
  since: '01/04/2016',
};

function buildDemoCelulares({ G, ayerA, DEVICE, INSURED }) {
  const event = ayerA(19, 40);
  const blockRequested = plus(event, 35);
  return [{
    overrides: { purchase_proof: { purchase: PURCHASE } },
    retailer: RETAILER,
    buyer: {
      dni: INSURED.dni,
      name: 'MONTENEGRO SOFIA',
      address: 'Av. Pedro Goyena 1240 Piso:5 Dpto:D - Ciudad de Buenos Aires',
    },
    item: {
      code: 'SAM-A16-128NG',
      description: `Celular Samsung ${DEVICE.model} 128GB 4GB RAM Negro`,
      details: [`IMEI: ${DEVICE.imei}`, `N° de serie: ${DEVICE.serial} - Garantía oficial 12 meses`],
    },
    place: 'Av. Rivadavia 5400 - Caballito',
    folder: 'robo',
    claimCause: 'Robo en vía pública',
    documents: ['police_report', 'purchase_proof'],
    event,
    claimedAmount: 429999,
    eventLocation: 'Av. Rivadavia 5400, a la salida de la estación Primera Junta de la línea A',
    locality: 'Caballito',
    province: 'Ciudad Autónoma de Buenos Aires',
    description:
      'Salía del subte en Primera Junta mirando el celular. Un hombre que venía corriendo me lo '
      + 'arrancó de la mano y escapó por Emilio Mitre. Pedí el bloqueo del IMEI desde la compu de '
      + 'una amiga y fui a hacer la denuncia a la comisaría de Caballito.',
    police: {
      at: plus(event, 75),
      number: '6A-2026-011207',
      folio: '84',
      station: 'Comisaría Vecinal 6-A — Caballito',
      stationAddress: 'Av. Pedro Goyena 1580, Ciudad Autónoma de Buenos Aires — Tel. (011) 5555-0160',
      officer: 'Of. Ppal. Natalia S. Ferraro (Leg. 40.517)',
      officerShort: 'Of. Ppal. N. S. Ferraro',
      caratula: 'ROBO (art. 164 del Código Penal de la Nación)',
      fiscalia: 'N° 9',
      place: [
        'Av. Rivadavia al 5400, acceso a la estación Primera Junta,',
        '             barrio de Caballito, C.A.B.A. — vía pública.',
      ],
      relato: (hora) => [
        `Que siendo aproximadamente las ${hora} horas del día de la fecha, ${G.el} denunciante`,
        'salía de la estación Primera Junta de la línea A por el acceso de Av. Rivadavia al',
        '5400, con su teléfono celular en la mano. Que en esas circunstancias un masculino que',
        'se acercó corriendo desde atrás le arrancó el teléfono de un tirón y se dio a la fuga a',
        `pie por la calle Emilio Mitre. Que ${G.el} denunciante no sufrió lesiones que requirieran`,
        `asistencia médica y no fue amenazad${G.a} con arma alguna. Que describe al autor como de`,
        'contextura delgada, con campera oscura con capucha. Que sobre el acceso a la estación',
        'existen cámaras del Sistema de Monitoreo Público de la Ciudad.',
      ],
      camaras: true,
      // The real-looking acta (documentos-demo.js).
      ministry: 'Gobierno de la Ciudad de Buenos Aires - Ministerio de Seguridad',
      agency: 'POLICÍA DE LA CIUDAD',
      numberLabel: 'Actuación N°',
      prosecutor: 'Fiscalía PCyF N° 9',
      signer: 'Of. Ppal. Natalia S. Ferraro - Leg. 40.517',
      stampLines: ['POLICÍA DE LA CIUDAD', 'COM. VECINAL 6-A', 'CABALLITO'],
      opening: 'En la Ciudad Autónoma de Buenos Aires',
      manifiesta:
        'Que en el día de la fecha, siendo aproximadamente las 19:40 horas, salía de la estación '
        + 'Primera Junta de la línea A de subterráneos por el acceso ubicado en Av. Rivadavia al 5400, '
        + 'de este barrio de Caballito, con su teléfono celular en la mano. Que en esas circunstancias '
        + 'un masculino que se acercó corriendo desde atrás le arrancó el teléfono de un tirón y se dio '
        + 'a la fuga a pie por la calle Emilio Mitre en dirección a Av. Directorio. Que no logró '
        + 'alcanzarlo. Que desde la computadora de una amiga solicitó a la empresa prestataria el '
        + `bloqueo de la línea y del IMEI, a las ${hm(blockRequested)} horas. Que el equipo se encuentra `
        + 'asegurado. Que es todo cuanto tiene para manifestar.',
      preguntas: [
        ['para que diga si puede aportar datos del autor',
          'Que se trata de un masculino de unos 25 años, de contextura delgada, que vestía campera '
          + 'oscura con capucha y zapatillas blancas. Que no podría reconocerlo.'],
        [`para que diga si resultó lesionad${G.a}`, `Que no. Que no fue amenazad${G.a} con armas.`],
        ['para que describa el objeto sustraído',
          `Que se trata de un teléfono celular marca SAMSUNG, modelo ${DEVICE.model}, color negro, de `
          + `${DEVICE.storage}, IMEI ${DEVICE.imei}, línea ${INSURED.phone}, adquirido el ${PURCHASE.date}.`],
        ['para que diga si existen testigos o cámaras',
          'Que no conoce testigos. Que en el acceso a la estación hay cámaras de seguridad.'],
      ],
    },
    // Cited by the police report; the IMEI block certificate itself isn't part of the first round.
    block: { requested: blockRequested },
    expected: {
      perfil: 'demoBbva',
      cambia: 'robo de un Samsung Galaxy A16 en Primera Junta, ayer — caso de Fast Track de la presentación',
      antes: 'correr scripts/reset-asegurados-de-prueba.sql si Sofía ya tiene expedientes',
      seDetectaEn: [
        'rule_result: criterios de FAST_TRACK en PASS (ratio 0,48; 0 siniestros previos; póliza al día)',
      ],
      resultadoEsperado: 'FAST_TRACK',
      notas: [],
    },
  }];
}

module.exports = { buildDemoCelulares };
