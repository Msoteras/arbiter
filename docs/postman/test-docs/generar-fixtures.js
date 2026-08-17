// Genera los 4 PDFs de la agenda documental del caso Fast Track (BBVA · Celulares · Robo en vía
// pública) desde un único objeto CASE, para que los datos no puedan divergir entre documentos.
//
//   police_report        denuncia_policial_fast_track.pdf
//   purchase_proof       factura_compra_fast_track.pdf
//   imei_deregistration  baja_imei_fast_track.pdf
//   last_connection      ultima_conexion_fast_track.pdf
//
// El motor PDF (Helvetica + WinAnsiEncoding, texto seleccionable) vive en lib-pdf.js, compartido
// con el generador de Tecnología Portátil.
// Uso: node generar-fixtures.js <directorio-destino>

const fs = require('fs');
const path = require('path');
const { plus, d, hm, hms, iso, pdfDate, cuit, Page, letterhead, footer, build } = require('./lib-pdf');

// ─────────────────────────────────────────────────────────────────────────────
// Fechas — relativas a la corrida, no fijas.
//
// `cases.reported_at` es @CreationTimestamp: la regla D11 (TemporalRuleEvaluator) compara
// `reportedAt - eventDate` contra las 72 hs de `coverage.report_deadline_hours`. Con una fecha de
// hecho fija, el fixture caduca: a las 72 hs de esa fecha el caso deja de dar FAST_TRACK y pasa a
// tener el Fast Track bloqueado por regla temporal.
//
// Por eso el hecho se ancla a **ayer a las 19:25**: fecha relativa, hora fija. Un desplazamiento
// puro ("hace N horas") deja el robo a cualquier hora —a las 3 de la mañana el relato de "consultaba
// la app de transporte" no se sostiene—, mientras que anclar la hora del día da siempre un robo al
// caer la tarde y un intervalo de entre 24 y 49 hs respecto de la corrida: cómodo contra las 72,
// corras a la hora que corras.
// ─────────────────────────────────────────────────────────────────────────────
const NOW = new Date();
const EVENT_AT = new Date(NOW);
EVENT_AT.setDate(EVENT_AT.getDate() - 1);
EVENT_AT.setHours(19, 25, 0, 0);

const POLICE_REPORT_AT = plus(EVENT_AT, 135);   // +2 h 15 min → D12 (plazo denuncia policial) holgado
const BLOCK_REQUESTED_AT = plus(EVENT_AT, 50);  // llamó a la operadora antes de ir a la comisaría
const BLOCK_EFFECTIVE_AT = plus(EVENT_AT, 82);
const LAST_CONNECTION_AT = plus(EVENT_AT, 6, 42);   // el equipo siguió en red unos minutos y se apagó
// Constancias emitidas a la mañana siguiente (10:00), salvo que la corrida sea antes de esa hora:
// un documento no puede estar fechado en el futuro respecto del momento en que se genera el set.
const CARRIER_ISSUE_AT = new Date(Math.min(plus(EVENT_AT, 875).getTime(), NOW.getTime()));

// ─────────────────────────────────────────────────────────────────────────────
// Datos del caso — única fuente de verdad para los cuatro documentos
// ─────────────────────────────────────────────────────────────────────────────
const CASE = {
  insured: {
    formal: 'SOTERAS, Martina',
    display: 'Martina Soteras',
    dni: '42.987.654',
    cuil: '27-42987654-1',
    birth: '14/03/1996',
    address: 'Av. Rivadavia 3150, piso 4° "B", C.A.B.A.',
    phone: '11-5555-0001',
    email: 'martina.soteras@example.com',
  },
  device: {
    brand: 'SAMSUNG',
    model: 'Galaxy A56 5G',
    color: 'Gris (Awesome Graphite)',
    storage: '256 GB',
    imei: '356938035643809',
  },
  line: '11-5555-0001',
  event: {
    date: d(EVENT_AT),
    time: hm(EVENT_AT),
    iso: iso(EVENT_AT),
    place: 'Av. Rivadavia al 2800, intersección con calle Pasco, barrio de Balvanera, C.A.B.A.',
  },
  policeReport: {
    at: `${d(POLICE_REPORT_AT)}, ${hm(POLICE_REPORT_AT)} hs.`,
    iso: iso(POLICE_REPORT_AT),
    number: '3B-2026-014782',
    station: 'Comisaría Vecinal 3-B — Balvanera',
    officer: 'Of. Sub. Rodrigo A. Benítez (Leg. 41.207)',
  },
  block: {
    requestedAt: `${d(BLOCK_REQUESTED_AT)}, ${hm(BLOCK_REQUESTED_AT)} hs.`,
    effectiveAt: `${d(BLOCK_EFFECTIVE_AT)}, ${hm(BLOCK_EFFECTIVE_AT)} hs.`,
    issuedOn: d(CARRIER_ISSUE_AT),
  },
  lastConnection: {
    at: `${d(LAST_CONNECTION_AT)}, ${hms(LAST_CONNECTION_AT)} hs.`,
    site: 'BAL-0472 — Av. Rivadavia 2750, C.A.B.A.',
    lac: '21407',
    cid: '55318',
  },
  purchase: {
    date: '30/12/2025',
    invoice: '0003-00041827',
    unitPrice: '589.999,00',
    net: '487.602,48',
    vat: '102.396,52',
  },
  // Empresas ficticias a propósito: no queremos un comprobante que aparente ser de una empresa real.
  retailer: { name: 'Electrocenter Argentina S.R.L.', address: 'Av. Corrientes 1840, C.A.B.A.' },
  carrier: { name: 'Nexo Móvil Argentina S.A.', address: 'Av. Leandro N. Alem 855, C.A.B.A.' },
  policyNumber: 'POL-CEL-2026-042',
};

CASE.retailer.cuit = cuit('30', '71284563');
CASE.carrier.cuit = cuit('30', '69158742');

// ─────────────────────────────────────────────────────────────────────────────
// 1 · police_report — acta de denuncia
// ─────────────────────────────────────────────────────────────────────────────
function policeReport() {
  const p = new Page();
  const { insured, device, event, policeReport } = CASE;

  p.text('POLICÍA DE LA CIUDAD DE BUENOS AIRES', { font: 'F2', size: 12, center: true, leading: 13 });
  p.text('Comisaría Vecinal 3-B — Balvanera', { size: 9, center: true });
  p.text('Av. Jujuy 332, Ciudad Autónoma de Buenos Aires — Tel. (011) 4931-1122', { size: 8.5, center: true });
  p.gap(4).rule().gap(8);
  p.text('ACTA DE DENUNCIA', { font: 'F2', size: 13, center: true, leading: 14 });
  p.text(`Actuación N° ${policeReport.number} — Libro de Guardia, folio 214`, { size: 9, center: true });
  p.gap(6).rule();

  p.section('DATOS DE LA ACTUACIÓN');
  p.field('Fecha y hora de recepción:', policeReport.at);
  p.field('Funcionario interviniente:', policeReport.officer);
  p.field('Carátula provisoria:', 'ROBO (art. 164 del Código Penal de la Nación)');
  p.gap(5);

  p.section('DATOS DE LA DENUNCIANTE');
  p.field('Apellido y nombre:', insured.formal);
  p.field('Documento:', `DNI ${insured.dni} — CUIL ${insured.cuil}`);
  p.field('Nacionalidad:', `argentina — Fecha de nacimiento: ${insured.birth}`);
  p.field('Domicilio:', insured.address);
  p.field('Teléfono:', `${insured.phone} — Correo: ${insured.email}`);
  p.gap(5);

  p.section('DATOS DEL HECHO');
  p.field('Fecha y hora del hecho:', `${event.date}, aproximadamente ${event.time} hs.`);
  p.field('Lugar:', 'Av. Rivadavia al 2800, intersección con calle Pasco,');
  p.text('             barrio de Balvanera, C.A.B.A. — vía pública.', { size: 9 });
  p.gap(5);

  p.section('RELATO DE LA DENUNCIANTE');
  [
    `Que siendo aproximadamente las ${event.time} horas del día de la fecha, la denunciante se`,
    'encontraba caminando por la vereda de Av. Rivadavia al 2800 en dirección al oeste,',
    'sosteniendo su teléfono celular en la mano derecha mientras consultaba una aplicación',
    'de transporte público. Que en esas circunstancias fue abordada por un masculino que',
    'circulaba en bicicleta, quien mediante un tirón le arrebató el aparato de la mano y se',
    'dio a la fuga por calle Pasco en dirección al sur. Que la denunciante no sufrió',
    'lesiones ni fue amenazada con arma alguna, y no puede aportar mayores datos',
    'filiatorios del autor, describiéndolo como de aproximadamente 25 años, contextura',
    'delgada, campera oscura y gorra. Que sobre la intersección mencionada existen',
    'cámaras del Sistema de Monitoreo Público de la Ciudad.',
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(5);

  p.section('OBJETO SUSTRAÍDO');
  p.text(`Un (1) teléfono celular marca ${device.brand}, modelo ${device.model}, color gris,`, { size: 9 });
  p.text(`capacidad ${device.storage}, IMEI ${device.imei}, línea ${CASE.line}.`, { size: 9 });
  p.text(`La denunciante manifiesta haber solicitado el bloqueo de la línea y del IMEI ante`, { size: 9 });
  p.text(`la empresa prestataria del servicio el mismo día del hecho, a las ${hm(BLOCK_REQUESTED_AT)} hs.`, { size: 9 });
  p.gap(5);

  p.section('CONSTANCIAS');
  p.text('•  Se dio intervención a la Fiscalía Penal, Contravencional y de Faltas N° 8 del', { size: 9 });
  p.text('   Poder Judicial de la C.A.B.A.', { size: 9 });
  p.text('•  Se solicitó el resguardo de las imágenes de las cámaras del Sistema de Monitoreo', { size: 9 });
  p.text('   Público correspondientes a la fecha y franja horaria del hecho.', { size: 9 });
  p.text('•  Se extiende la presente constancia a la denunciante a los fines que estime', { size: 9 });
  p.text('   corresponder ante su compañía aseguradora.', { size: 9 });
  p.gap(14);
  p.text('Previa lectura y ratificación, firma la denunciante por ante el funcionario actuante.', { size: 9 });
  p.gap(26);
  p.text('...........................................                    ...........................................', { size: 9 });
  p.text(`        ${insured.display}                                     Of. Sub. R. A. Benítez`, { size: 8.5 });
  p.text(`        DNI ${insured.dni}                                      Comisaría Vecinal 3-B`, { size: 8.5 });

  footer(p);
  return build(p, {
    title: `Acta de denuncia ${policeReport.number}`,
    author: 'Policia de la Ciudad de Buenos Aires',
    subject: 'Robo en via publica - documento simulado de prueba',
    created: pdfDate(POLICE_REPORT_AT),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 2 · purchase_proof — factura de compra del equipo
// ─────────────────────────────────────────────────────────────────────────────
function purchaseProof() {
  const p = new Page();
  const { insured, device, purchase, retailer } = CASE;

  letterhead(p, retailer.name, `${retailer.address} — Tel. (011) 4372-9900`, retailer.cuit);

  p.text('FACTURA  B', { font: 'F2', size: 14, center: true, leading: 15 });
  p.text('Documento no válido como crédito fiscal — Consumidor Final', { size: 8, center: true });
  p.gap(8);

  p.field('Comprobante N°:', purchase.invoice);
  p.field('Fecha de emisión:', purchase.date);
  p.field('Condición frente al IVA:', 'Responsable Inscripto');
  p.field('Ingresos Brutos:', '901-284563-7    Inicio de actividades: 03/2011');
  p.gap(6).rule();

  p.section('DATOS DEL COMPRADOR');
  p.field('Apellido y nombre:', insured.formal);
  p.field('Documento:', `DNI ${insured.dni} — CUIL ${insured.cuil}`);
  p.field('Domicilio:', insured.address);
  p.field('Condición frente al IVA:', 'Consumidor Final');
  p.gap(8).rule();

  p.section('DETALLE');
  p.gap(2);
  p.text('Cant.   Descripción', { font: 'F2', size: 8.5, leading: 12 });
  p.text('  1     Teléfono celular SAMSUNG Galaxy A56 5G — 256 GB — Gris (Awesome Graphite)', { size: 9 });
  p.text(`        IMEI ${device.imei}`, { size: 9 });
  p.text('        Nro. de serie: RZ8W60K3XPL — Garantía oficial 12 meses', { size: 9 });
  p.gap(8);

  p.moneyRow('Subtotal (neto gravado)', `$ ${purchase.net}`);
  p.moneyRow('IVA 21%', `$ ${purchase.vat}`);
  p.gap(2);
  p.moneyRow('TOTAL', `$ ${purchase.unitPrice}`, { size: 11, bold: true, leading: 16 });

  p.rule();
  p.field('Forma de pago:', 'Tarjeta de crédito — 12 cuotas sin interés');
  p.field('CAE N°:', '75298431660284');
  p.field('Vencimiento del CAE:', '09/01/2026');
  p.gap(10);

  p.text('El presente comprobante acredita la titularidad del equipo detallado. Conservar para', { size: 8.5 });
  p.text('gestiones de garantía o ante la compañía aseguradora.', { size: 8.5 });

  footer(p);
  return build(p, {
    title: `Factura B ${purchase.invoice}`,
    author: retailer.name,
    subject: 'Comprobante de compra del equipo - documento simulado de prueba',
    created: "20251230113000-03'00'",
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 3 · imei_deregistration — constancia de bloqueo / baja de IMEI
// ─────────────────────────────────────────────────────────────────────────────
function imeiDeregistration() {
  const p = new Page();
  const { insured, device, block, policeReport, carrier } = CASE;

  letterhead(p, carrier.name, `${carrier.address} — Atención al cliente 0800-333-6396`, carrier.cuit);

  p.text('CONSTANCIA DE BLOQUEO Y BAJA DE EQUIPO', { font: 'F2', size: 12.5, center: true, leading: 14 });
  p.text('Registro Nacional de Equipos Móviles Sustraídos o Extraviados', { size: 8.5, center: true });
  p.gap(6).rule();

  p.field('Constancia N°:', 'BLQ-2026-0884517');
  p.field('Fecha de emisión:', block.issuedOn);
  p.gap(6);

  p.section('DATOS DEL TITULAR DE LA LÍNEA');
  p.field('Apellido y nombre:', insured.formal);
  p.field('Documento:', `DNI ${insured.dni}`);
  p.field('Línea:', CASE.line);
  p.field('Plan:', 'Nexo Full 20 GB — pospago');
  p.gap(6);

  p.section('DATOS DEL EQUIPO');
  p.field('Marca y modelo:', `${device.brand} ${device.model}`);
  p.field('Capacidad / color:', `${device.storage} — ${device.color}`);
  p.field('IMEI:', device.imei);
  p.gap(6);

  p.section('DATOS DE LA GESTIÓN');
  p.field('Motivo:', 'Robo del equipo en la vía pública');
  p.field('Solicitud recibida:', block.requestedAt);
  p.field('Canal:', 'Línea de atención telefónica — gestión N° 6114982');
  p.field('Bloqueo efectivizado:', block.effectiveAt);
  p.field('Estado actual del IMEI:', 'BLOQUEADO — informado al registro nacional');
  p.field('Actuación policial:', `N° ${policeReport.number}, ${policeReport.station}`);
  p.gap(10);

  p.section('ALCANCE DEL BLOQUEO');
  [
    'A partir de la fecha y hora de efectivización, el equipo identificado con el IMEI',
    'consignado queda inhabilitado para operar en las redes de telefonía móvil del país,',
    'cualquiera sea la tarjeta SIM que se le coloque. La línea asociada fue suspendida en',
    'el mismo acto y su titularidad se mantiene a nombre de la solicitante.',
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(8);
  [
    'Se deja constancia de que la solicitud de bloqueo fue recibida con anterioridad a la',
    'radicación de la denuncia policial, conforme lo declarado por la titular al momento',
    'del reclamo telefónico.',
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(10);

  p.text('La presente se extiende a pedido de la titular para ser presentada ante su compañía', { size: 8.5 });
  p.text('aseguradora.', { size: 8.5 });
  p.gap(26);
  p.text('...........................................', { size: 9 });
  p.text('        Mesa de Gestiones', { size: 8.5 });
  p.text(`        ${carrier.name}`, { size: 8.5 });

  footer(p);
  return build(p, {
    title: 'Constancia de bloqueo y baja de IMEI BLQ-2026-0884517',
    author: carrier.name,
    subject: 'Baja de IMEI por robo - documento simulado de prueba',
    created: pdfDate(CARRIER_ISSUE_AT),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 4 · last_connection — constancia de última conexión de la línea
// ─────────────────────────────────────────────────────────────────────────────
function lastConnection() {
  const p = new Page();
  const { insured, device, lastConnection: lc, carrier } = CASE;

  letterhead(p, carrier.name, `${carrier.address} — Área Técnica / Registros de Red`, carrier.cuit);

  p.text('CONSTANCIA DE ÚLTIMO REGISTRO EN RED', { font: 'F2', size: 12.5, center: true, leading: 14 });
  p.gap(6).rule();

  p.field('Constancia N°:', 'REG-2026-0884518');
  p.field('Fecha de emisión:', CASE.block.issuedOn);
  p.field('Solicitada por:', `${insured.display}, DNI ${insured.dni}`);
  p.gap(6);

  p.section('EQUIPO Y LÍNEA CONSULTADOS');
  p.field('Línea:', CASE.line);
  p.field('IMEI:', device.imei);
  p.field('Equipo:', `${device.brand} ${device.model} — ${device.storage}`);
  p.gap(6);

  p.section('ÚLTIMOS REGISTROS DE RED');
  p.gap(2);
  p.text('Fecha y hora                Evento                        Celda / sitio', { font: 'F2', size: 8.5, leading: 12 });
  // Segundos "sucios" a propósito: un log de red real no cae en :00 redondo.
  [
    [plus(EVENT_AT, -33, 10), 'Actualización de ubicación', 'BAL-0468 — Rivadavia 3100'],
    [plus(EVENT_AT, -7, 33), 'Datos móviles (sesión)     ', 'BAL-0472 — Rivadavia 2750'],
    [plus(EVENT_AT, 1, 5), 'Actualización de ubicación', 'BAL-0472 — Rivadavia 2750'],
    [LAST_CONNECTION_AT, 'Desconexión de red        ', 'BAL-0472 — Rivadavia 2750'],
  ].forEach(([when, event, site]) =>
    p.text(`${d(when)}  ${hms(when)}        ${event}    ${site}`, { size: 8.5 }));
  p.gap(10);

  p.section('RESULTADO DE LA CONSULTA');
  p.field('Último registro:', lc.at);
  p.field('Sitio:', lc.site);
  p.field('LAC / CID:', `${lc.lac} / ${lc.cid}`);
  p.field('Registros posteriores:', 'ninguno');
  p.field('Estado de la línea:', 'suspendida por bloqueo (ver constancia BLQ-2026-0884517)');
  p.gap(10);

  [
    'No se registran conexiones del IMEI consultado a la red de esta prestadora con',
    'posterioridad a la fecha y hora indicadas. El sitio consignado corresponde a la',
    'antena que dio servicio al equipo en su último registro y su cobertura abarca las',
    'inmediaciones de Av. Rivadavia entre Pasco y Alberti, C.A.B.A.',
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(8);
  [
    'La ubicación informada corresponde al área de cobertura de la celda y no a una',
    'posición exacta del equipo.',
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(24);

  p.text('...........................................', { size: 9 });
  p.text('        Área Técnica — Registros de Red', { size: 8.5 });
  p.text(`        ${carrier.name}`, { size: 8.5 });

  footer(p);
  return build(p, {
    title: 'Constancia de ultimo registro en red REG-2026-0884518',
    author: carrier.name,
    subject: 'Ultima conexion de la linea - documento simulado de prueba',
    created: pdfDate(CARRIER_ISSUE_AT),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
const outDir = process.argv[2];
if (!outDir) {
  console.error('Uso: node make-docs.js <directorio-destino>');
  process.exit(1);
}

const documents = [
  ['denuncia_policial_fast_track.pdf', policeReport(), 'police_report'],
  ['factura_compra_fast_track.pdf', purchaseProof(), 'purchase_proof'],
  ['baja_imei_fast_track.pdf', imeiDeregistration(), 'imei_deregistration'],
  ['ultima_conexion_fast_track.pdf', lastConnection(), 'last_connection'],
];

for (const [name, bytes, type] of documents) {
  fs.writeFileSync(path.join(outDir, name), bytes);
  console.log(`${type.padEnd(20)} ${name.padEnd(38)} ${bytes.length} bytes`);
}

// El payload sale del mismo CASE que los PDFs: si las fechas del expediente y las de los
// documentos se separan, el caso deja de ser coherente y nadie se entera hasta leerlos.
const payload = {
  branch: 'Celulares',
  product: 'Celular Protegido Premium',
  claimCause: 'Robo en vía pública',
  insuredItem: 'Samsung Galaxy A56 5G 256GB',
  insuredId: CASE.insured.dni,
  policyNumber: CASE.policyNumber,
  description:
    'Caminaba por Av. Rivadavia al 2800 con el celular en la mano consultando la app de transporte '
    + 'cuando un hombre en bicicleta me lo arrebató y escapó por calle Pasco. Hice la denuncia policial '
    + 'esa misma noche en la Comisaría Vecinal 3-B y pedí el bloqueo del IMEI a la compañía telefónica.',
  eventDate: CASE.event.iso,
  eventLocation: 'Av. Rivadavia 2800, Balvanera, C.A.B.A.',
  policeReportAt: CASE.policeReport.iso,
  claimedAmount: 620000,
  pep: false,
  imageConsent: true,
  contactEmail: CASE.insured.email,
  contactPhone: CASE.insured.phone,
};
fs.writeFileSync(path.join(outDir, 'caso_fast_track.json'), JSON.stringify(payload, null, 2) + '\n');
console.log(`${'case (payload)'.padEnd(20)} ${'caso_fast_track.json'.padEnd(38)} eventDate=${CASE.event.iso}`);

const expiresAt = new Date(EVENT_AT.getTime() + 72 * 3600_000);
const hoursAgo = Math.round((NOW - EVENT_AT) / 3600_000);
console.log(`\nCUIT comercio: ${CASE.retailer.cuit}   CUIT operadora: ${CASE.carrier.cuit}`);
console.log(`Hecho: ${CASE.event.date} ${CASE.event.time} (hace ${hoursAgo} h)`);
console.log(`El set da FAST_TRACK hasta ${d(expiresAt)} ${hm(expiresAt)} — después D11 (plazo de`);
console.log('denuncia, 72 hs) bloquea el Fast Track. Volvé a correr este script para renovarlo.');
