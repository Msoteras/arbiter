// History sequence for Camila (perfiles.js, `camila`): three claims filed IN ORDER and WITHOUT
// resetting in between, so each one is evaluated with the previous ones as prior claims. It's the
// counterpart of the mutations, which reset before every case: here the history is the variable.
//
//   1-primer-robo    robo, yesterday 10:05    0 prior claims  → the clean baseline
//   2-segundo-robo   robo, yesterday 19:25    1 prior claim   → Fast Track's maxPriorClaims (0)
//   3-hurto          hurto, yesterday 13:40   2 prior claims  → Hurto's annual cap (1), D10
//
// All three the same day on purpose: the first step's report deadline (D11, 72 h) is what
// expires the sequence, and an earlier first event would leave it barely a day of life.
//
// Why these rules and not others: every case filed in Arbiter becomes a prior claim of the insured
// (ClassificationOrchestrator.withArbiterAntecedents), and two rules read that list today — the
// Fast Track gate counts all of them, and MAX_EVENTS_YEAR counts those of the branch whose event
// falls within the 12 months before this one (by DATE, not time: step 2 counts for step 3 even
// though its event is later the same day). The expected outcomes assume the BBVA configuration
// of 22/09/2026: robo coverage capped at 2 events a year, hurto at 1.

const { plus } = require('./lib-pdf');

function buildHistory({ robo, hurto }) {
  const step = (folder, scenario, expected) => ({
    ...scenario,
    folder: `historial/${folder}`,
    expected: { paso: folder, perfil: 'camila', ...expected },
  });

  // The first robo is the same day as the regular one, in the morning: two thefts hours apart is
  // the pattern the history rules exist for. Its own numbers, so the cases don't share a police
  // report or a block request.
  const firstEvent = new Date(robo.event);
  firstEvent.setHours(10, 5, 0, 0);
  const firstRobo = {
    ...robo,
    event: firstEvent,
    police: { ...robo.police, at: plus(firstEvent, 135), number: '3B-2026-014511', folio: '203' },
    block: {
      ...robo.block,
      requested: plus(firstEvent, 50),
      effective: plus(firstEvent, 82),
      number: 'BLQ-2026-0881204',
      gestion: '6109877',
    },
    lastConnection: { ...robo.lastConnection, number: 'REG-2026-0881205', at: plus(firstEvent, 6, 42) },
  };

  return [
    step('1-primer-robo', firstRobo, {
      cambia: 'robo en vía pública, ayer a la mañana — el primer siniestro de la asegurada',
      antes: 'correr scripts/reset-asegurados-de-prueba.sql: la secuencia arranca con el historial en cero',
      seDetectaEn: ['rule_result: criterios de FAST_TRACK en PASS, maxPriorClaims con 0 previos'],
      resultadoEsperado: 'FAST_TRACK',
      notas: [],
    }),

    step('2-segundo-robo', robo, {
      cambia: 'otro robo en vía pública, ayer — ahora hay un siniestro previo',
      antes: 'NO limpiar: el paso 1 tiene que estar cargado',
      seDetectaEn: [
        'rule_result: maxPriorClaims en FAIL (1 previo, máximo 0)',
        'rule_result: MAX_EVENTS_YEAR en PASS (2.º evento en 12 meses, tope de la cobertura de robo: 2)',
        'risk_breakdown: claim_frequency mayor que 0',
        'factores del LLM: debería mencionar el robo de esa misma mañana',
      ],
      resultadoEsperado: 'Sale de Fast Track por el siniestro previo → LLM_SOLICITA_REVISION_MANUAL o LLM_NO_RECOMIENDA_APROBAR',
      notas: [
        'Es el mismo robo que camila/celulares/robo: lo único distinto respecto de correrlo limpio es el historial.',
      ],
    }),

    step('3-hurto', hurto, {
      cambia: 'hurto en el subte, ayer al mediodía — tercer evento del ramo en el mismo día',
      antes: 'NO limpiar: los pasos 1 y 2 tienen que estar cargados',
      seDetectaEn: [
        'rule_result: MAX_EVENTS_YEAR en FAIL — "Supera el tope de 1 siniestro(s) por año: 2 siniestro(s) previo(s)"',
        'rule_result: maxPriorClaims en FAIL (2 previos)',
        'risk_breakdown: claim_frequency saturado',
      ],
      resultadoEsperado: 'Fast Track bloqueado por el tope anual (D10) → LLM_NO_RECOMIENDA_APROBAR',
      notas: [
        'El robo del paso 2 cuenta aunque su hecho sea más tarde ese mismo día: la ventana compara fechas, no horas. '
          + 'El orden que importa es el de carga, no el de los hechos.',
        'También falla el ratio del gate (620.000 sobre 650.000 de la cobertura de hurto, 0,95): '
          + 'el paso no está pensado para eso, el tope anual es la señal que interesa.',
      ],
    }),
  ];
}

module.exports = { buildHistory };
