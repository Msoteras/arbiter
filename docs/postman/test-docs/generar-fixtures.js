// Genera los fixtures del ramo **Celulares** — un juego por hecho generador, con exactamente los
// documentos que la agenda documental le pide a cada uno
// (db/init-multitenant.sql, document_requirement 1-14):
//
//   robo/                police_report · purchase_proof · imei_deregistration · last_connection
//   hurto/               police_report · purchase_proof · imei_deregistration · last_connection
//   caida/               purchase_proof · repair_quote · item_photo (*)
//   rotura accidental/   purchase_proof · repair_quote · item_photo (*)
//
// (*) `item_photo` no se genera: es una imagen. Para Celulares está `foto_equipo_para_fraude.jpg`
//     en la raíz de test-docs — una foto real del mismo A56 de la póliza. Adjuntarla desde ahí.
//
// Todos los documentos de un caso salen de un único objeto de escenario, así que no pueden
// contradecirse entre sí. El motor PDF vive en lib-pdf.js; quién firma, en perfiles.js.
//
// Uso: node generar-fixtures.js [directorio-destino] [--sin-marca]
//      Sin argumentos escribe en docs/postman/test-docs/conMarcaDePrueba/celulares/<hecho>/

const fs = require('fs');
const path = require('path');
const { plus, d, hm, hms, iso, pdfDate, cuit, Page, letterhead, footer, build, MARGIN } = require('./lib-pdf');
const { PROFILES, variantFromArgv, outDirFromArgv } = require('./perfiles');

const VARIANT = variantFromArgv();
const PROFILE = PROFILES[VARIANT];
const G = PROFILE.g;
const INSURED = PROFILE.insured;

// ─────────────────────────────────────────────────────────────────────────────
// Fechas — relativas a la corrida, no fijas.
//
// `cases.reported_at` es @CreationTimestamp: la regla D11 (TemporalRuleEvaluator) compara
// `reportedAt - eventDate` contra las 72 hs de `coverage.report_deadline_hours`. Con una fecha de
// hecho fija, el fixture caduca: a las 72 hs de esa fecha el caso deja de dar FAST_TRACK y pasa a
// tener el Fast Track bloqueado por regla temporal.
//
// Por eso cada hecho se ancla a **ayer** a una hora fija propia: fecha relativa, hora elegida para
// que el relato cierre (un robo al caer la tarde, un hurto en el subte al mediodía). Un
// desplazamiento puro ("hace N horas") pondría el hecho a las 3 de la mañana la mitad de las veces.
// ─────────────────────────────────────────────────────────────────────────────
const NOW = new Date();

function ayerA(hora, minuto) {
  const x = new Date(NOW);
  x.setDate(x.getDate() - 1);
  x.setHours(hora, minuto, 0, 0);
  return x;
}

/** Constancias emitidas a la mañana siguiente, salvo que la corrida sea antes de esa hora: un
 *  documento no puede estar fechado en el futuro respecto del momento en que se genera el set. */
function emitidaLaMananaSiguiente(evento, minutos) {
  return new Date(Math.min(plus(evento, minutos).getTime(), NOW.getTime()));
}

// ─────────────────────────────────────────────────────────────────────────────
// Constantes del ramo — el equipo, la póliza y las empresas son las mismas en los cuatro casos
// ─────────────────────────────────────────────────────────────────────────────
const BRANCH = 'Celulares';
const PRODUCT = 'Celular Protegido Premium';
const POLICY_NUMBER = 'POL-CEL-2026-042';

const DEVICE = {
  brand: 'SAMSUNG',
  model: 'Galaxy A56 5G',
  color: 'Gris (Awesome Graphite)',
  storage: '256 GB',
  imei: '356938035643809',
  serial: 'RZ8W60K3XPL',
};

const LINE = INSURED.phone;

// La compra es una sola: el equipo se compró una vez y la misma factura acredita titularidad en los
// cuatro casos.
const PURCHASE = {
  date: '30/12/2025',
  invoice: '0003-00041827',
  unitPrice: '589.999,00',
  net: '487.602,48',
  vat: '102.396,52',
  cae: '75298431660284',
  caeDue: '09/01/2026',
};

// Empresas ficticias a propósito: no queremos comprobantes que aparenten ser de una empresa real.
const RETAILER = { name: 'Electrocenter Argentina S.R.L.', address: 'Av. Corrientes 1840, C.A.B.A.' };
const CARRIER = { name: 'Nexo Móvil Argentina S.A.', address: 'Av. Leandro N. Alem 855, C.A.B.A.' };
const REPAIR_SHOP = { name: 'MicroFix Servicio Técnico S.R.L.', address: 'Av. Corrientes 2450, C.A.B.A.' };

RETAILER.cuit = cuit('30', '71284563');
CARRIER.cuit = cuit('30', '69158742');
REPAIR_SHOP.cuit = cuit('30', '70558193');

// ─────────────────────────────────────────────────────────────────────────────
// Los cuatro escenarios
// ─────────────────────────────────────────────────────────────────────────────

// ── Robo en vía pública ──────────────────────────────────────────────────────
const ROBO_EVENT = ayerA(19, 25);
const ROBO = {
  folder: 'robo',
  claimCause: 'Robo en vía pública',
  documents: ['police_report', 'purchase_proof', 'imei_deregistration', 'last_connection'],
  event: ROBO_EVENT,
  claimedAmount: 620000,
  eventLocation: 'Av. Rivadavia 2800, entre Pasco y Alberti',
  locality: 'Balvanera',
  province: 'Ciudad Autónoma de Buenos Aires',
  description:
    'Caminaba por Av. Rivadavia al 2800 con el celular en la mano consultando la app de transporte '
    + 'cuando un hombre en bicicleta me lo arrebató y escapó por calle Pasco. Hice la denuncia policial '
    + 'esa misma noche en la Comisaría Vecinal 3-B y pedí el bloqueo del IMEI a la compañía telefónica.',
  police: {
    at: plus(ROBO_EVENT, 135),          // +2 h 15 min → D12 (plazo denuncia policial) holgado
    number: '3B-2026-014782',
    folio: '214',
    station: 'Comisaría Vecinal 3-B — Balvanera',
    stationAddress: 'Av. Jujuy 332, Ciudad Autónoma de Buenos Aires — Tel. (011) 4931-1122',
    officer: 'Of. Sub. Rodrigo A. Benítez (Leg. 41.207)',
    officerShort: 'Of. Sub. R. A. Benítez',
    caratula: 'ROBO (art. 164 del Código Penal de la Nación)',
    fiscalia: 'N° 8',
    place: [
      'Av. Rivadavia al 2800, intersección con calle Pasco,',
      '             barrio de Balvanera, C.A.B.A. — vía pública.',
    ],
    relato: (hora) => [
      `Que siendo aproximadamente las ${hora} horas del día de la fecha, ${G.el} denunciante se`,
      'encontraba caminando por la vereda de Av. Rivadavia al 2800 en dirección al oeste,',
      'sosteniendo su teléfono celular en la mano derecha mientras consultaba una aplicación',
      `de transporte público. Que en esas circunstancias fue abordad${G.a} por un masculino que`,
      'circulaba en bicicleta, quien mediante un tirón le arrebató el aparato de la mano y se',
      `dio a la fuga por calle Pasco en dirección al sur. Que ${G.el} denunciante no sufrió`,
      `lesiones ni fue amenazad${G.a} con arma alguna, y no puede aportar mayores datos`,
      'filiatorios del autor, describiéndolo como de aproximadamente 25 años, contextura',
      'delgada, campera oscura y gorra. Que sobre la intersección mencionada existen',
      'cámaras del Sistema de Monitoreo Público de la Ciudad.',
    ],
    camaras: true,
  },
  block: {
    motivo: 'Robo del equipo en la vía pública',
    requested: plus(ROBO_EVENT, 50),    // llamó a la operadora antes de ir a la comisaría
    effective: plus(ROBO_EVENT, 82),
    number: 'BLQ-2026-0884517',
    gestion: '6114982',
    canal: 'Línea de atención telefónica',
    // El bloqueo se pidió antes de la denuncia: el orden importa y el documento lo declara.
    antesDeLaDenuncia: true,
  },
  lastConnection: {
    number: 'REG-2026-0884518',
    at: plus(ROBO_EVENT, 6, 42),        // el equipo siguió en red unos minutos y se apagó
    site: 'BAL-0472 — Av. Rivadavia 2750, C.A.B.A.',
    lac: '21407',
    cid: '55318',
    cobertura: 'Av. Rivadavia entre Pasco y Alberti, C.A.B.A.',
    registros: [
      [-33, 10, 'Actualización de ubicación', 'BAL-0468 — Rivadavia 3100'],
      [-7, 33, 'Datos móviles (sesión)     ', 'BAL-0472 — Rivadavia 2750'],
      [1, 5, 'Actualización de ubicación', 'BAL-0472 — Rivadavia 2750'],
      [6, 42, 'Desconexión de red        ', 'BAL-0472 — Rivadavia 2750'],
    ],
  },
};

// ── Hurto ────────────────────────────────────────────────────────────────────
// La diferencia con el robo no es de grado sino de figura: el hurto es sustracción SIN violencia
// (art. 162), así que el relato no puede tener tirón, forcejeo ni amenaza — y la víctima se entera
// después. Eso corre toda la línea de tiempo: primero se advierte la falta, después se bloquea.
const HURTO_EVENT = ayerA(13, 40);
const HURTO = {
  folder: 'hurto',
  claimCause: 'Hurto',
  documents: ['police_report', 'purchase_proof', 'imei_deregistration', 'last_connection'],
  event: HURTO_EVENT,
  claimedAmount: 620000,
  eventLocation: 'Estación Ángel Gallardo, Línea B de subterráneos, andén dirección Juan Manuel de Rosas',
  locality: 'Villa Crespo',
  province: 'Ciudad Autónoma de Buenos Aires',
  description:
    'Viajaba en el subte línea B en hora pico, con el celular en el bolsillo exterior del abrigo. '
    + 'Al bajar en Ángel Gallardo quise usarlo y ya no estaba; no sentí nada ni vi a nadie. Fui a la '
    + 'comisaría esa misma tarde y pedí el bloqueo del IMEI apenas llegué a mi casa.',
  police: {
    at: plus(HURTO_EVENT, 175),         // +2 h 55 min: primero buscó el equipo, después denunció
    number: '15A-2026-007209',
    folio: '86',
    station: 'Comisaría Vecinal 15-A — Villa Crespo',
    stationAddress: 'Av. Corrientes 5877, Ciudad Autónoma de Buenos Aires — Tel. (011) 4771-4900',
    officer: 'Of. Ppal. Vanina L. Ocampo (Leg. 39.514)',
    officerShort: 'Of. Ppal. V. L. Ocampo',
    caratula: 'HURTO (art. 162 del Código Penal de la Nación)',
    fiscalia: 'N° 21',
    place: [
      'Estación Ángel Gallardo de la Línea B de subterráneos,',
      '             andén dirección Juan Manuel de Rosas, barrio de Villa Crespo, C.A.B.A.',
    ],
    relato: (hora) => [
      `Que siendo aproximadamente las ${hora} horas del día de la fecha, ${G.el} denunciante se`,
      'trasladaba en una formación de la Línea B de subterráneos, ascendida en la estación',
      'Carlos Gardel, llevando su teléfono celular en el bolsillo exterior de su abrigo. Que la',
      'formación circulaba con gran cantidad de pasajeros de pie, en horario de alta demanda.',
      `Que al descender en la estación Ángel Gallardo ${G.el} denunciante advirtió la ausencia`,
      'del aparato, sin haber percibido contacto, forcejeo ni maniobra alguna sobre su',
      `persona. Que no fue abordad${G.a} ni amenazad${G.a}, y no puede sindicar autor ni aportar`,
      'descripción alguna. Que revisó sus pertenencias y recorrió el andén sin resultado, y que',
      'consultó en la boletería de la estación por objetos entregados, con resultado negativo.',
      'Que la estación cuenta con cámaras de seguridad de la concesionaria del servicio.',
    ],
    camaras: false,
  },
  block: {
    motivo: 'Hurto del equipo en transporte público',
    requested: plus(HURTO_EVENT, 310),  // recién al llegar a su casa, después de la comisaría
    effective: plus(HURTO_EVENT, 331),
    number: 'BLQ-2026-0884962',
    gestion: '6117430',
    canal: 'Autogestión web — sesión validada con clave',
    antesDeLaDenuncia: false,
  },
  lastConnection: {
    number: 'REG-2026-0884963',
    at: plus(HURTO_EVENT, 21, 8),       // siguió en red hasta que salió del subte y lo apagaron
    site: 'VCR-0311 — Av. Corrientes 5600, C.A.B.A.',
    lac: '21455',
    cid: '61094',
    cobertura: 'Av. Corrientes entre Dorrego y Scalabrini Ortiz, C.A.B.A.',
    registros: [
      [-46, 22, 'Actualización de ubicación', 'ABA-0190 — Corrientes 3400'],
      [-12, 51, 'Sin cobertura (túnel)     ', '—'],
      [14, 37, 'Actualización de ubicación', 'VCR-0311 — Corrientes 5600'],
      [21, 8, 'Desconexión de red        ', 'VCR-0311 — Corrientes 5600'],
    ],
  },
};

// ── Caída ────────────────────────────────────────────────────────────────────
// Sin denuncia policial: no hubo delito. La agenda pide comprobante de compra, presupuesto y foto.
const CAIDA_EVENT = ayerA(8, 50);
const CAIDA = {
  folder: 'caida',
  claimCause: 'Caída',
  documents: ['purchase_proof', 'repair_quote'],
  event: CAIDA_EVENT,
  claimedAmount: 203000,
  eventLocation: 'Av. Warnes 2300, sobre la vereda, al descender del colectivo',
  locality: 'La Paternal',
  province: 'Ciudad Autónoma de Buenos Aires',
  description:
    'Al bajar del colectivo se me resbaló el celular de la mano y cayó de pantalla contra el cordón '
    + 'de la vereda. El vidrio quedó astillado y el táctil dejó de responder en la mitad inferior. '
    + 'Lo llevé al service esa misma mañana y me hicieron el presupuesto.',
  repair: {
    number: 'PRE-2026-004417',
    order: 'OR-2026-11284',
    received: plus(CAIDA_EVENT, 95),
    issued: plus(CAIDA_EVENT, 170),
    diagnostico: [
      'Se recibe el equipo encendido, con el módulo de pantalla astillado en su ángulo inferior',
      'izquierdo y fisuras que se extienden hacia el centro del panel. El digitalizador no',
      'responde al tacto en el tercio inferior. Se verifica que el marco presenta una marca de',
      'impacto compatible con una caída sobre superficie dura desde altura de mano. La placa',
      'principal enciende y la batería conserva su capacidad: el daño está acotado al módulo.',
    ],
    items: [
      ['Módulo de pantalla AMOLED original con marco — repuesto', '168.000,00'],
      ['Mano de obra: desarmado, reemplazo y calibración táctil', '35.000,00'],
    ],
    total: '203.000,00',
    observaciones: [
      'El equipo permanece en depósito del taller a la espera de la conformidad del cliente. No',
      'se inició la reparación. Presupuesto sin cargo, válido por 15 días corridos desde su',
      'emisión. Los repuestos son originales del fabricante y llevan 6 meses de garantía.',
    ],
  },
};

// ── Rotura accidental ────────────────────────────────────────────────────────
// El otro daño accidental del ramo, deliberadamente distinto de la caída: ingreso de líquido, con
// un presupuesto más caro y un diagnóstico que sugiere que la reparación está al límite.
const ROTURA_EVENT = ayerA(20, 40);
const ROTURA = {
  folder: 'rotura accidental',
  claimCause: 'Rotura accidental',
  documents: ['purchase_proof', 'repair_quote'],
  event: ROTURA_EVENT,
  claimedAmount: 333000,
  eventLocation: 'Av. Rivadavia 3150, piso 4° "B" — domicilio particular',
  locality: 'Almagro',
  province: 'Ciudad Autónoma de Buenos Aires',
  description:
    'Estaba trabajando en el escritorio y volqué un vaso de agua sobre el celular. Lo apagué y lo '
    + 'sequé, pero ya no volvió a encender. Al día siguiente lo llevé al service y el presupuesto '
    + 'dice que entró líquido a la placa.',
  repair: {
    number: 'PRE-2026-004431',
    order: 'OR-2026-11319',
    received: plus(ROTURA_EVENT, 760),
    issued: plus(ROTURA_EVENT, 860),
    diagnostico: [
      'Se recibe el equipo apagado, sin respuesta a la carga ni a la combinación de encendido',
      'forzado. Al abrirlo se constata oxidación en los conectores de la placa principal y',
      'activación de los testigos de humedad del alojamiento de la batería y del puerto de',
      'carga, compatible con ingreso de líquido. Se realiza limpieza ultrasónica y se recupera',
      'imagen por breves lapsos, con reinicios sucesivos: la placa está comprometida.',
    ],
    items: [
      ['Placa principal reacondicionada con garantía — repuesto', '285.000,00'],
      ['Limpieza ultrasónica, diagnóstico y mano de obra', '48.000,00'],
    ],
    total: '333.000,00',
    observaciones: [
      'Se deja constancia de que el daño por ingreso de líquido puede manifestar fallas',
      'posteriores no detectables al momento del diagnóstico. El equipo permanece en el taller;',
      'no se inició la reparación. Presupuesto válido por 15 días corridos desde su emisión.',
    ],
  },
};

const SCENARIOS = [ROBO, HURTO, CAIDA, ROTURA];

// ─────────────────────────────────────────────────────────────────────────────
// 1 · police_report — acta de denuncia
// ─────────────────────────────────────────────────────────────────────────────
function policeReport(sc) {
  const p = new Page();
  const { police } = sc;

  p.text('POLICÍA DE LA CIUDAD DE BUENOS AIRES', { font: 'F2', size: 12, center: true, leading: 13 });
  p.text(police.station, { size: 9, center: true });
  p.text(police.stationAddress, { size: 8.5, center: true });
  p.gap(4).rule().gap(8);
  p.text('ACTA DE DENUNCIA', { font: 'F2', size: 13, center: true, leading: 14 });
  p.text(`Actuación N° ${police.number} — Libro de Guardia, folio ${police.folio}`, { size: 9, center: true });
  p.gap(6).rule();

  p.section('DATOS DE LA ACTUACIÓN');
  p.field('Fecha y hora de recepción:', `${d(police.at)}, ${hm(police.at)} hs.`);
  p.field('Funcionario interviniente:', police.officer);
  p.field('Carátula provisoria:', police.caratula);
  p.gap(5);

  p.section(`DATOS ${G.DEL} DENUNCIANTE`);
  p.field('Apellido y nombre:', INSURED.formal);
  p.field('Documento:', `DNI ${INSURED.dni} — CUIL ${INSURED.cuil}`);
  p.field('Nacionalidad:', `argentin${G.a} — Fecha de nacimiento: ${INSURED.birth}`);
  p.field('Domicilio:', INSURED.address);
  p.field('Teléfono:', `${INSURED.phone} — Correo: ${INSURED.email}`);
  p.gap(5);

  p.section('DATOS DEL HECHO');
  p.field('Fecha y hora del hecho:', `${d(sc.event)}, aproximadamente ${hm(sc.event)} hs.`);
  p.field('Lugar:', police.place[0]);
  police.place.slice(1).forEach((l) => p.text(l, { size: 9 }));
  p.gap(5);

  p.section(`RELATO ${G.DEL} DENUNCIANTE`);
  police.relato(hm(sc.event)).forEach((l) => p.text(l, { size: 9 }));
  p.gap(5);

  p.section('OBJETO SUSTRAÍDO');
  p.text(`Un (1) teléfono celular marca ${DEVICE.brand}, modelo ${DEVICE.model}, color gris,`, { size: 9 });
  p.text(`capacidad ${DEVICE.storage}, IMEI ${DEVICE.imei}, línea ${LINE}.`, { size: 9 });
  p.text(`${G.El} denunciante manifiesta haber solicitado el bloqueo de la línea y del IMEI ante`, { size: 9 });
  p.text(`la empresa prestataria del servicio a las ${hm(sc.block.requested)} hs. del ${d(sc.block.requested)}.`, { size: 9 });
  p.gap(5);

  p.section('CONSTANCIAS');
  p.text(`•  Se dio intervención a la Fiscalía Penal, Contravencional y de Faltas ${police.fiscalia} del`, { size: 9 });
  p.text('   Poder Judicial de la C.A.B.A.', { size: 9 });
  if (police.camaras) {
    p.text('•  Se solicitó el resguardo de las imágenes de las cámaras del Sistema de Monitoreo', { size: 9 });
    p.text('   Público correspondientes a la fecha y franja horaria del hecho.', { size: 9 });
  } else {
    p.text('•  Se libró oficio a la concesionaria del servicio de subterráneos a fin de requerir el', { size: 9 });
    p.text('   resguardo de las imágenes de la estación y de la formación involucrada.', { size: 9 });
  }
  p.text(`•  Se extiende la presente constancia ${G.al} denunciante a los fines que estime`, { size: 9 });
  p.text('   corresponder ante su compañía aseguradora.', { size: 9 });
  p.gap(12);
  p.text(`Previa lectura y ratificación, firma ${G.el} denunciante por ante el funcionario actuante.`, { size: 9 });
  p.gap(24);
  p.text('...........................................                    ...........................................', { size: 9 });
  p.text(`        ${INSURED.display.padEnd(15)}                                     ${police.officerShort}`, { size: 8.5 });
  p.text(`        DNI ${INSURED.dni}                                      ${police.station.split(' — ')[0]}`, { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: `Acta de denuncia ${police.number}`,
    author: 'Policia de la Ciudad de Buenos Aires',
    subject: `${sc.claimCause} - documento simulado de prueba`,
    created: pdfDate(police.at),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 2 · purchase_proof — factura de compra del equipo
// ─────────────────────────────────────────────────────────────────────────────
function purchaseProof() {
  const p = new Page();

  letterhead(p, RETAILER.name, `${RETAILER.address} — Tel. (011) 4372-9900`, RETAILER.cuit);

  p.text('FACTURA  B', { font: 'F2', size: 14, center: true, leading: 15 });
  p.text('Documento no válido como crédito fiscal — Consumidor Final', { size: 8, center: true });
  p.gap(8);

  p.field('Comprobante N°:', PURCHASE.invoice);
  p.field('Fecha de emisión:', PURCHASE.date);
  p.field('Condición frente al IVA:', 'Responsable Inscripto');
  p.field('Ingresos Brutos:', '901-284563-7    Inicio de actividades: 03/2011');
  p.gap(6).rule();

  p.section('DATOS DEL COMPRADOR');
  p.field('Apellido y nombre:', INSURED.formal);
  p.field('Documento:', `DNI ${INSURED.dni} — CUIL ${INSURED.cuil}`);
  p.field('Domicilio:', INSURED.address);
  p.field('Condición frente al IVA:', 'Consumidor Final');
  p.gap(8).rule();

  p.section('DETALLE');
  p.gap(2);
  p.text('Cant.   Descripción', { font: 'F2', size: 8.5, leading: 12 });
  p.text(`  1     Teléfono celular ${DEVICE.brand} ${DEVICE.model} — ${DEVICE.storage} — ${DEVICE.color}`, { size: 9 });
  p.text(`        IMEI ${DEVICE.imei}`, { size: 9 });
  p.text(`        Nro. de serie: ${DEVICE.serial} — Garantía oficial 12 meses`, { size: 9 });
  p.gap(8);

  p.moneyRow('Subtotal (neto gravado)', `$ ${PURCHASE.net}`);
  p.moneyRow('IVA 21%', `$ ${PURCHASE.vat}`);
  p.gap(2);
  p.moneyRow('TOTAL', `$ ${PURCHASE.unitPrice}`, { size: 11, bold: true, leading: 16 });

  p.rule();
  p.field('Forma de pago:', 'Tarjeta de crédito — 12 cuotas sin interés');
  p.field('CAE N°:', PURCHASE.cae);
  p.field('Vencimiento del CAE:', PURCHASE.caeDue);
  p.gap(10);

  p.text('El presente comprobante acredita la titularidad del equipo detallado. Conservar para', { size: 8.5 });
  p.text('gestiones de garantía o ante la compañía aseguradora.', { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: `Factura B ${PURCHASE.invoice}`,
    author: RETAILER.name,
    subject: 'Comprobante de compra del equipo - documento simulado de prueba',
    created: "20251230113000-03'00'",
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 3 · imei_deregistration — constancia de bloqueo / baja de IMEI
// ─────────────────────────────────────────────────────────────────────────────
function imeiDeregistration(sc) {
  const p = new Page();
  const { block } = sc;
  const issued = emitidaLaMananaSiguiente(sc.event, 875);

  letterhead(p, CARRIER.name, `${CARRIER.address} — Atención al cliente 0800-333-6396`, CARRIER.cuit);

  p.text('CONSTANCIA DE BLOQUEO Y BAJA DE EQUIPO', { font: 'F2', size: 12.5, center: true, leading: 14 });
  p.text('Registro Nacional de Equipos Móviles Sustraídos o Extraviados', { size: 8.5, center: true });
  p.gap(6).rule();

  p.field('Constancia N°:', block.number);
  p.field('Fecha de emisión:', d(issued));
  p.gap(6);

  p.section('DATOS DEL TITULAR DE LA LÍNEA');
  p.field('Apellido y nombre:', INSURED.formal);
  p.field('Documento:', `DNI ${INSURED.dni}`);
  p.field('Línea:', LINE);
  p.field('Plan:', 'Nexo Full 20 GB — pospago');
  p.gap(6);

  p.section('DATOS DEL EQUIPO');
  p.field('Marca y modelo:', `${DEVICE.brand} ${DEVICE.model}`);
  p.field('Capacidad / color:', `${DEVICE.storage} — ${DEVICE.color}`);
  p.field('IMEI:', DEVICE.imei);
  p.gap(6);

  p.section('DATOS DE LA GESTIÓN');
  p.field('Motivo:', block.motivo);
  p.field('Solicitud recibida:', `${d(block.requested)}, ${hm(block.requested)} hs.`);
  p.field('Canal:', `${block.canal} — gestión N° ${block.gestion}`);
  p.field('Bloqueo efectivizado:', `${d(block.effective)}, ${hm(block.effective)} hs.`);
  p.field('Estado actual del IMEI:', 'BLOQUEADO — informado al registro nacional');
  p.field('Actuación policial:', `N° ${sc.police.number}, ${sc.police.station}`);
  p.gap(10);

  p.section('ALCANCE DEL BLOQUEO');
  [
    'A partir de la fecha y hora de efectivización, el equipo identificado con el IMEI',
    'consignado queda inhabilitado para operar en las redes de telefonía móvil del país,',
    'cualquiera sea la tarjeta SIM que se le coloque. La línea asociada fue suspendida en',
    `el mismo acto y su titularidad se mantiene a nombre ${G.del} solicitante.`,
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(8);
  (block.antesDeLaDenuncia
    ? [
      'Se deja constancia de que la solicitud de bloqueo fue recibida con anterioridad a la',
      `radicación de la denuncia policial, conforme lo declarado ${G.por} titular al momento`,
      'del reclamo telefónico.',
    ]
    : [
      'Se deja constancia de que la solicitud de bloqueo fue recibida con posterioridad a la',
      `radicación de la denuncia policial, cuya actuación ${G.el} titular consignó al iniciar`,
      'la gestión por autogestión web.',
    ]).forEach((l) => p.text(l, { size: 9 }));
  p.gap(10);

  p.text(`La presente se extiende a pedido ${G.del} titular para ser presentada ante su compañía`, { size: 8.5 });
  p.text('aseguradora.', { size: 8.5 });
  p.gap(26);
  p.text('...........................................', { size: 9 });
  p.text('        Mesa de Gestiones', { size: 8.5 });
  p.text(`        ${CARRIER.name}`, { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: `Constancia de bloqueo y baja de IMEI ${block.number}`,
    author: CARRIER.name,
    subject: `Baja de IMEI por ${sc.claimCause.toLowerCase()} - documento simulado de prueba`,
    created: pdfDate(issued),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 4 · last_connection — constancia de última conexión de la línea
// ─────────────────────────────────────────────────────────────────────────────
function lastConnection(sc) {
  const p = new Page();
  const lc = sc.lastConnection;
  const issued = emitidaLaMananaSiguiente(sc.event, 875);

  letterhead(p, CARRIER.name, `${CARRIER.address} — Área Técnica / Registros de Red`, CARRIER.cuit);

  p.text('CONSTANCIA DE ÚLTIMO REGISTRO EN RED', { font: 'F2', size: 12.5, center: true, leading: 14 });
  p.gap(6).rule();

  p.field('Constancia N°:', lc.number);
  p.field('Fecha de emisión:', d(issued));
  p.field('Solicitada por:', `${INSURED.display}, DNI ${INSURED.dni}`);
  p.gap(6);

  p.section('EQUIPO Y LÍNEA CONSULTADOS');
  p.field('Línea:', LINE);
  p.field('IMEI:', DEVICE.imei);
  p.field('Equipo:', `${DEVICE.brand} ${DEVICE.model} — ${DEVICE.storage}`);
  p.gap(6);

  p.section('ÚLTIMOS REGISTROS DE RED');
  p.gap(2);
  p.text('Fecha y hora                Evento                        Celda / sitio', { font: 'F2', size: 8.5, leading: 12 });
  // Segundos "sucios" a propósito: un log de red real no cae en :00 redondo.
  lc.registros.forEach(([min, seg, evento, sitio]) => {
    const when = plus(sc.event, min, seg);
    p.text(`${d(when)}  ${hms(when)}        ${evento}    ${sitio}`, { size: 8.5 });
  });
  p.gap(10);

  p.section('RESULTADO DE LA CONSULTA');
  p.field('Último registro:', `${d(lc.at)}, ${hms(lc.at)} hs.`);
  p.field('Sitio:', lc.site);
  p.field('LAC / CID:', `${lc.lac} / ${lc.cid}`);
  p.field('Registros posteriores:', 'ninguno');
  p.field('Estado de la línea:', `suspendida por bloqueo (ver constancia ${sc.block.number})`);
  p.gap(10);

  [
    'No se registran conexiones del IMEI consultado a la red de esta prestadora con',
    'posterioridad a la fecha y hora indicadas. El sitio consignado corresponde a la',
    'antena que dio servicio al equipo en su último registro y su cobertura abarca las',
    `inmediaciones de ${lc.cobertura}`,
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(8);
  [
    'La ubicación informada corresponde al área de cobertura de la celda y no a una',
    'posición exacta del equipo.',
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(24);

  p.text('...........................................', { size: 9 });
  p.text('        Área Técnica — Registros de Red', { size: 8.5 });
  p.text(`        ${CARRIER.name}`, { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: `Constancia de ultimo registro en red ${lc.number}`,
    author: CARRIER.name,
    subject: 'Ultima conexion de la linea - documento simulado de prueba',
    created: pdfDate(issued),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 5 · repair_quote — presupuesto de reparación
//
// El documento que sostiene un reclamo por daño: acá el monto reclamado no es el valor del equipo
// sino el costo de arreglarlo, y el presupuesto es lo único que lo respalda.
// ─────────────────────────────────────────────────────────────────────────────
function repairQuote(sc) {
  const p = new Page();
  const r = sc.repair;

  letterhead(p, REPAIR_SHOP.name, `${REPAIR_SHOP.address} — Tel. (011) 4951-7700`, REPAIR_SHOP.cuit);

  p.text('PRESUPUESTO DE REPARACIÓN', { font: 'F2', size: 12.5, center: true, leading: 14 });
  p.text('Servicio técnico de telefonía celular — Consumidor Final', { size: 8.5, center: true });
  p.gap(6).rule();

  p.field('Presupuesto N°:', r.number);
  p.field('Orden de reparación:', r.order);
  p.field('Fecha de ingreso:', `${d(r.received)}, ${hm(r.received)} hs.`);
  p.field('Fecha de emisión:', `${d(r.issued)}, ${hm(r.issued)} hs.`);
  p.gap(6);

  p.section('DATOS DEL CLIENTE');
  p.field('Apellido y nombre:', INSURED.formal);
  p.field('Documento:', `DNI ${INSURED.dni}`);
  p.field('Teléfono de contacto:', INSURED.phone);
  p.gap(6);

  p.section('EQUIPO INGRESADO');
  p.field('Marca y modelo:', `${DEVICE.brand} ${DEVICE.model}`);
  p.field('Capacidad / color:', `${DEVICE.storage} — ${DEVICE.color}`);
  p.field('IMEI:', DEVICE.imei);
  p.field('Nro. de serie:', DEVICE.serial);
  p.field('Hecho declarado:', `${sc.claimCause} — ${d(sc.event)}, ${hm(sc.event)} hs.`);
  p.gap(6);

  p.section('DIAGNÓSTICO TÉCNICO');
  r.diagnostico.forEach((l) => p.text(l, { size: 9 }));
  p.gap(8);

  p.section('DETALLE DEL PRESUPUESTO');
  p.gap(2);
  r.items.forEach(([concepto, importe]) => p.moneyRow(concepto, `$ ${importe}`));
  p.gap(2);
  p.moneyRow('TOTAL (IVA incluido)', `$ ${r.total}`, { size: 11, bold: true, leading: 16 });
  p.rule();

  p.section('OBSERVACIONES');
  r.observaciones.forEach((l) => p.text(l, { size: 9 }));
  p.gap(22);

  p.text('...........................................', { size: 9 });
  p.text('        Departamento Técnico', { size: 8.5 });
  p.text(`        ${REPAIR_SHOP.name}`, { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: `Presupuesto de reparacion ${r.number}`,
    author: REPAIR_SHOP.name,
    subject: `Presupuesto por ${sc.claimCause.toLowerCase()} - documento simulado de prueba`,
    created: pdfDate(r.issued),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// Salida
// ─────────────────────────────────────────────────────────────────────────────
const BUILDERS = {
  police_report: [policeReport, 'denuncia_policial_celulares.pdf'],
  purchase_proof: [purchaseProof, 'factura_compra_celulares.pdf'],
  imei_deregistration: [imeiDeregistration, 'baja_imei_celulares.pdf'],
  last_connection: [lastConnection, 'ultima_conexion_celulares.pdf'],
  repair_quote: [repairQuote, 'presupuesto_reparacion_celulares.pdf'],
};

const root = outDirFromArgv() || path.join(__dirname, PROFILE.folder, 'celulares');
console.log(`Destino: ${root}\n`);

for (const sc of SCENARIOS) {
  const dir = path.join(root, sc.folder);
  fs.mkdirSync(dir, { recursive: true });
  console.log(`${sc.folder}/  (${sc.claimCause})`);

  for (const type of sc.documents) {
    const [builder, filename] = BUILDERS[type];
    const bytes = builder(sc);
    fs.writeFileSync(path.join(dir, filename), bytes);
    console.log(`  ${type.padEnd(20)} ${filename.padEnd(38)} ${bytes.length} bytes`);
  }

  // El payload sale del mismo escenario que los PDFs: si las fechas del expediente y las de los
  // documentos se separan, el caso deja de ser coherente y nadie se entera hasta leerlos.
  const payload = {
    branch: BRANCH,
    product: PRODUCT,
    claimCause: sc.claimCause,
    insuredItem: `${DEVICE.brand} ${DEVICE.model} ${DEVICE.storage}`,
    insuredId: INSURED.dni,
    policyNumber: POLICY_NUMBER,
    description: sc.description,
    eventDate: iso(sc.event),
    eventLocation: sc.eventLocation,
    locality: sc.locality,
    province: sc.province,
    ...(sc.police ? { policeReportAt: iso(sc.police.at) } : {}),
    claimedAmount: sc.claimedAmount,
    pep: false,
    imageConsent: true,
    contactEmail: INSURED.email,
    contactPhone: INSURED.phone,
  };
  const payloadName = `caso_${sc.folder.split(' ')[0]}.json`;
  fs.writeFileSync(path.join(dir, payloadName), JSON.stringify(payload, null, 2) + '\n');
  console.log(`  ${'case (payload)'.padEnd(20)} ${payloadName.padEnd(38)} ${d(sc.event)} ${hm(sc.event)}`);

  if (sc.documents.includes('repair_quote')) {
    console.log(`  ${'item_photo'.padEnd(20)} ${'../foto_equipo_para_fraude.jpg  (adjuntar desde la raíz)'}`);
  }
  console.log();
}

const expira = plus(ROBO.event, 72 * 60);
console.log(`Los sets con denuncia vencen el ${d(expira)} ${hm(expira)} — después D11 (plazo de`);
console.log('denuncia, 72 hs) bloquea el Fast Track. Volvé a correr este script para renovarlos.');
