// Layouts for the final presentation's documents (README §12): the police report and the invoice,
// drawn the way the real ones look, so the analyst on screen sees something close to what an
// insured sends.
//
//   facturaB      AFIP's electronic invoice B: "ORIGINAL" band, letter box with COD. 006, issuer and
//                 receiver boxes, item grid, totals, the transparency box of Ley 27.743 (IVA
//                 contenido) and the QR with the CAE. Amounts without thousands separators, as
//                 AFIP prints them.
//   actaDenuncia  the record a police station hands over: header of the force, data box of the
//                 proceeding, the statement in the usual form ("comparece... MANIFIESTA: Que...",
//                 "PREGUNTADA... RESPONDE"), closing formula, signatures, stamp and initials in ink.
//
// Separate from the regular layouts on purpose: the mutations cover fields at fixed positions
// (importe-pegado, fecha-pegada), so those layouts can't move. People, shops and numbers are
// fictitious; the PDF metadata still marks the file as a fixture.

const { esc, Page, build, afipQrUrl, footer, d, hm, pdfDate, PAGE_W } = require('./lib-pdf');
const { encode: encodeQr } = require('./qr');

const INK = '0.12 0.22 0.55';
// Baseline of the acta's "copy for the complainant" line, above the footer's area.
const MARGIN_FOOT = 70;

// ─── Drawing primitives ─────────────────────────────────────────────────────

/** Approximate Helvetica width: capitals are wider than the average, spaces narrower. */
function width(str, size, font = 'F1') {
  let units = 0;
  for (const ch of str) {
    if (ch === ' ') units += 0.278;
    else if (/[A-ZÁÉÍÓÚÑ]/.test(ch)) units += 0.68;
    else if (/[0-9]/.test(ch)) units += 0.556;
    else if (/[il.,:;'|!]/.test(ch)) units += 0.27;
    else if (/[mwMW]/.test(ch)) units += 0.83;
    else units += 0.55;
  }
  return units * size * (font === 'F2' ? 1.12 : 1);
}

function txt(p, str, x, y, { font = 'F1', size = 8, right = false, center = false } = {}) {
  let px = x;
  if (right) px = x - width(str, size, font);
  if (center) px = x - width(str, size, font) / 2;
  p.ops.push(`BT /${font} ${size} Tf ${px.toFixed(2)} ${y.toFixed(2)} Td (${esc(str)}) Tj ET`);
}

/** Bold label followed by its value on the same baseline. */
function labeled(p, label, value, x, y, size = 8) {
  txt(p, label, x, y, { font: 'F2', size });
  txt(p, value, x + width(label, size, 'F2') + size * 0.6, y, { size });
}

function rect(p, x, y, w, h, { fill = null, width = 0.7 } = {}) {
  if (fill !== null) p.ops.push(`${fill} g ${x} ${y} ${w} ${h} re f 0 g`);
  p.ops.push(`${width} w ${x} ${y} ${w} ${h} re S`);
}

function line(p, x1, y1, x2, y2, width = 0.7) {
  p.ops.push(`${width} w ${x1} ${y1} m ${x2} ${y2} l S`);
}

/** Splits a paragraph into lines that fit `width` points at `size`. */
function wrap(str, maxWidth, size, font = 'F1') {
  const lines = [];
  let current = '';
  for (const word of str.split(' ')) {
    if (current && width(current + ' ' + word, size, font) > maxWidth) {
      lines.push(current);
      current = word;
    } else {
      current = current ? `${current} ${word}` : word;
    }
  }
  if (current) lines.push(current);
  return lines;
}

/** Amounts as AFIP prints them on the invoice: no thousands separator. */
const afip = (amount) => amount.replace(/\./g, '');

// ─── Factura B ──────────────────────────────────────────────────────────────

/**
 * @param retailer { name, legalName, address, cuit, iibb, since }
 * @param purchase { date, invoice, total, vat, cae, caeDue, payment, created }
 * @param buyer    { dni, name, address }
 * @param item     { code, description, details: [] }
 */
function facturaB({ retailer, purchase, buyer, item, disclaimer }) {
  const p = new Page();
  const L = 36, R = PAGE_W - 36, W = R - L, MID = PAGE_W / 2;
  const [ptoVta, nro] = purchase.invoice.split('-');

  // ORIGINAL band.
  rect(p, L, 780, W, 22);
  txt(p, 'ORIGINAL', MID, 787, { font: 'F2', size: 11, center: true });

  // Issuer box, split by the letter box.
  rect(p, L, 640, W, 140);
  line(p, MID, 640, MID, 742);
  rect(p, MID - 23, 742, 46, 38, { fill: 1 });
  txt(p, 'B', MID, 756, { font: 'F2', size: 24, center: true });
  txt(p, 'COD. 006', MID, 746, { font: 'F2', size: 6.5, center: true });

  wrap(retailer.name, MID - L - 40, 13, 'F2').forEach((l, i) => txt(p, l, L + 12, 755 - i * 15, { font: 'F2', size: 13 }));
  // Label and value; the value wraps under itself so it never crosses the letter box's line.
  const issuerField = (label, value, y) => {
    const indent = width(label, 8, 'F2') + 8 * 0.6;
    wrap(value, MID - L - 24 - indent, 8).forEach((l, i) => {
      if (i === 0) labeled(p, label, l, L + 12, y);
      else txt(p, l, L + 12 + indent, y - i * 10, { size: 8 });
    });
  };
  issuerField('Razón Social:', retailer.legalName, 704);
  issuerField('Domicilio Comercial:', retailer.address, 682);
  labeled(p, 'Condición frente al IVA:', 'IVA Responsable Inscripto', L + 12, 660);

  txt(p, 'FACTURA', MID + 30, 756, { font: 'F2', size: 17 });
  labeled(p, 'Punto de Venta:', ptoVta.padStart(5, '0'), MID + 30, 730, 8.5);
  labeled(p, 'Comp. Nro:', nro, MID + 150, 730, 8.5);
  labeled(p, 'Fecha de Emisión:', purchase.date, MID + 30, 716, 8.5);
  labeled(p, 'CUIT:', retailer.cuit.replace(/-/g, ''), MID + 30, 694);
  labeled(p, 'Ingresos Brutos:', retailer.iibb, MID + 30, 681);
  labeled(p, 'Fecha de Inicio de Actividades:', retailer.since, MID + 30, 668);

  // Billed period.
  rect(p, L, 614, W, 22);
  labeled(p, 'Período Facturado Desde:', purchase.date, L + 8, 621);
  labeled(p, 'Hasta:', purchase.date, L + 200, 621);
  labeled(p, 'Fecha de Vto. para el pago:', purchase.date, L + 320, 621);

  // Receiver.
  rect(p, L, 546, W, 64);
  labeled(p, 'DNI:', buyer.dni.replace(/\./g, ''), L + 8, 594);
  labeled(p, 'Apellido y Nombre / Razón Social:', buyer.name, L + 160, 594);
  labeled(p, 'Condición frente al IVA:', 'Consumidor Final', L + 8, 578);
  labeled(p, 'Domicilio:', buyer.address, L + 200, 578);
  labeled(p, 'Condición de venta:', purchase.payment, L + 8, 562);

  // Item grid.
  const cols = [
    ['Código', L + 4, false], ['Producto / Servicio', L + 82, false], ['Cantidad', L + 312, true],
    ['U. Medida', L + 322, false], ['Precio Unit.', L + 420, true], ['% Bonif', L + 455, true],
    ['Subtotal', R - 4, true],
  ];
  rect(p, L, 520, W, 20, { fill: 0.82 });
  cols.forEach(([h, x, right]) => txt(p, h, x, 527, { font: 'F2', size: 7.5, right }));
  const total = afip(purchase.total);
  txt(p, item.code, L + 4, 506, { size: 7.5 });
  txt(p, item.description, L + 82, 506, { size: 7.5 });
  item.details.forEach((l, i) => txt(p, l, L + 82, 496 - i * 9.5, { size: 7 }));
  txt(p, '1,00', L + 312, 506, { size: 7.5, right: true });
  txt(p, 'unidades', L + 322, 506, { size: 7.5 });
  txt(p, total, L + 420, 506, { size: 7.5, right: true });
  txt(p, '0,00', L + 455, 506, { size: 7.5, right: true });
  txt(p, total, R - 4, 506, { size: 7.5, right: true });

  // Totals and the consumer transparency regime.
  rect(p, L, 150, W, 120);
  [['Subtotal: $', total], ['Importe Otros Tributos: $', '0,00'], ['Importe Total: $', total]].forEach(([l, v], i) => {
    const font = i === 2 ? 'F2' : 'F1';
    txt(p, l, R - 90, 248 - i * 16, { font: 'F2', size: 9, right: true });
    txt(p, v, R - 8, 248 - i * 16, { font, size: 9, right: true });
  });
  rect(p, L + 8, 158, 250, 44);
  txt(p, 'Régimen de Transparencia Fiscal al Consumidor (Ley 27.743)', L + 14, 190, { font: 'F2', size: 7 });
  labeled(p, 'IVA Contenido: $', afip(purchase.vat), L + 14, 177, 7.5);
  labeled(p, 'Otros Impuestos Nacionales Indirectos: $', '0,00', L + 14, 165, 7.5);

  // QR, CAE, page. Pinned below the body floor: that space is the footer's, and the AFIP block
  // sits there on a real invoice.
  // Since 2025 the invoices carry ARCA's name, AFIP's successor; the QR still points to afip.gob.ar.
  p.at(144);
  p.qr(encodeQr(afipQrUrl({
    date: purchase.date, cuitIssuer: retailer.cuit, invoice: purchase.invoice, invoiceType: 6,
    total: purchase.total, dniRecipient: buyer.dni, cae: purchase.cae,
  })), 70, { x: L + 4 });
  txt(p, 'ARCA', L + 86, 124, { font: 'F2', size: 14 });
  txt(p, 'Comprobante Autorizado', L + 86, 110, { font: 'F2', size: 9 });
  txt(p, 'Esta Agencia no se responsabiliza por los datos', L + 86, 98, { size: 6.5 });
  txt(p, 'ingresados en el detalle de la operación', L + 86, 90, { size: 6.5 });
  txt(p, 'Pág. 1/1', MID + 10, 110, { font: 'F2', size: 8.5, center: true });
  labeled(p, 'CAE N°:', purchase.cae, R - 160, 110, 8.5);
  labeled(p, 'Fecha de Vto. de CAE:', purchase.caeDue, R - 160, 96, 8.5);

  if (disclaimer) footer(p);
  return build(p, {
    title: `Factura B ${ptoVta}-${nro}`,
    author: retailer.name,
    subject: 'Comprobante de compra del equipo - documento simulado de prueba',
    created: purchase.created,
  });
}

// ─── Acta de denuncia ───────────────────────────────────────────────────────

const UNITS = ['', 'uno', 'dos', 'tres', 'cuatro', 'cinco', 'seis', 'siete', 'ocho', 'nueve', 'diez',
  'once', 'doce', 'trece', 'catorce', 'quince', 'dieciséis', 'diecisiete', 'dieciocho', 'diecinueve',
  'veinte', 'veintiuno', 'veintidós', 'veintitrés', 'veinticuatro', 'veinticinco', 'veintiséis',
  'veintisiete', 'veintiocho', 'veintinueve', 'treinta', 'treinta y uno'];
const MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre',
  'octubre', 'noviembre', 'diciembre'];

/** "a los diecisiete días del mes de noviembre del año dos mil veintiséis" */
function fechaEnLetras(date) {
  const day = date.getDate();
  const dias = day === 1 ? 'al primer día' : `a los ${UNITS[day]} días`;
  return `${dias} del mes de ${MONTHS[date.getMonth()]} del año dos mil ${UNITS[date.getFullYear() - 2000]}`;
}

function ageAt(birth, date) {
  const [dd, mm, yyyy] = birth.split('/').map(Number);
  let age = date.getFullYear() - yyyy;
  if (date.getMonth() + 1 < mm || (date.getMonth() + 1 === mm && date.getDate() < dd)) age--;
  return age;
}

/** Hand-made initials: a few overlapping curves in ink. */
function signature(p, x, y, seed) {
  const r = (i) => ((Math.sin(seed * 12.9898 + i * 78.233) * 43758.5453) % 1 + 1) % 1;
  const pts = [];
  for (let i = 0; i < 7; i++) pts.push([x + i * 13 + r(i) * 6, y + (i % 2 ? 10 : -2) + r(i + 9) * 8]);
  let path = `${pts[0][0].toFixed(1)} ${pts[0][1].toFixed(1)} m`;
  for (let i = 1; i < pts.length; i++) {
    const [px, py] = pts[i - 1], [cx, cy] = pts[i];
    path += ` ${(px + 6).toFixed(1)} ${(py + 12).toFixed(1)} ${(cx - 6).toFixed(1)} ${(cy - 12).toFixed(1)} ${cx.toFixed(1)} ${cy.toFixed(1)} c`;
  }
  p.ops.push(`q ${INK} RG 0.9 w 1 J ${path} S ${x - 4} ${y - 4} m ${x + 92} ${y + 2} l S Q`);
}

/** The station's round stamp in ink: two rings and the name across. */
function stamp(p, cx, cy, lines) {
  const k = 0.5523;
  const circle = (r) => `${cx + r} ${cy} m ${cx + r} ${cy + r * k} ${cx + r * k} ${cy + r} ${cx} ${cy + r} c `
    + `${cx - r * k} ${cy + r} ${cx - r} ${cy + r * k} ${cx - r} ${cy} c `
    + `${cx - r} ${cy - r * k} ${cx - r * k} ${cy - r} ${cx} ${cy - r} c `
    + `${cx + r * k} ${cy - r} ${cx + r} ${cy - r * k} ${cx + r} ${cy} c S`;
  p.ops.push(`q ${INK} RG ${INK} rg 1.1 w ${circle(40)} 0.5 w ${circle(35)}`);
  lines.forEach((l, i) => txt(p, l, cx, cy + 9 - i * 9, { font: 'F2', size: i === 1 ? 6.5 : 4.6, center: true }));
  p.ops.push('Q');
}

/**
 * @param police   { agency, ministry, station, stationAddress, number, numberLabel, prosecutor,
 *                   caratula, at, signer, opening, manifiesta, preguntas, stampLines }
 * @param insured  perfiles.js insured, plus { civil, job }
 * @param event    the event's Date; place: one line
 */
function actaDenuncia({ police, insured, g, event, place, disclaimer }) {
  const p = new Page();
  const L = 56, R = PAGE_W - 56, W = R - L;
  const size = 8.6, lead = 11;
  const write = (str, { font = 'F1', indent = 0 } = {}) => {
    wrap(str, W - indent, size, font).forEach((l) => p.text(l, { font, size, x: L + indent, leading: lead }));
  };

  p.text(police.ministry, { size: 7.5, center: true, leading: 15 });
  p.text(police.agency, { font: 'F2', size: 12.5, center: true, leading: 14 });
  p.text(police.station.toUpperCase(), { font: 'F2', size: 9, center: true, leading: 11 });
  p.text(police.stationAddress, { size: 7.5, center: true, leading: 9 });
  p.gap(4).rule(0.8);

  // Data box of the proceeding.
  const top = p.y + 4;
  rect(p, L, top - 50, W, 50);
  line(p, L + W / 2, top - 50, L + W / 2, top);
  labeled(p, `${police.numberLabel}:`, police.number, L + 6, top - 12);
  labeled(p, 'Inicio:', `${d(police.at)} ${hm(police.at)} hs.`, L + W / 2 + 6, top - 12);
  labeled(p, 'Carátula:', police.caratula, L + 6, top - 26);
  labeled(p, 'Interviene:', police.prosecutor, L + W / 2 + 6, top - 26);
  labeled(p, 'Fecha y hora del hecho:', `${d(event)} - ${hm(event)} hs.`, L + 6, top - 40);
  labeled(p, 'Lugar:', place, L + W / 2 + 6, top - 40);
  p.y = top - 66;

  p.text('ACTA DE DENUNCIA', { font: 'F2', size: 11.5, center: true, leading: 16 });

  const age = ageAt(insured.birth, police.at);
  write(`${police.opening}, ${fechaEnLetras(police.at)}, siendo las ${hm(police.at)} horas, comparece ante `
    + 'la Instrucción una persona que dice llamarse '
    + `${insured.display.toUpperCase()}, de nacionalidad argentina, de ${age} años de edad, de estado civil `
    + `${insured.civil}, de profesión ${insured.job}, que sabe leer y escribir, titular del DNI N° ${insured.dni}, `
    + `con domicilio en ${insured.address}, teléfono ${insured.phone}, correo electrónico ${insured.email}. `
    + `Impuest${g.a} de las penalidades previstas en los artículos 245 y 275 del Código Penal, y preguntad${g.a} `
    + `si tiene algo que denunciar, MANIFIESTA: ${police.manifiesta}`);
  p.gap(3);
  police.preguntas.forEach(([q, a]) => {
    write(`PREGUNTAD${g.a.toUpperCase()}: ${q}. RESPONDE: ${a}`);
    p.gap(2);
  });
  p.gap(2);
  write(`No siendo para más, se da por finalizado el acto, que previa íntegra lectura es ratificado y firmado `
    + `por ${g.el} compareciente, quien recibe copia de la presente, por ante mí, que CERTIFICO.------------`);

  // Signatures, stamp, initials.
  // Signed right under the closing formula, as on paper.
  const sy = Math.max(p.y - 70, 120);
  signature(p, L + 20, sy + 12, insured.dni.length + insured.display.length);
  signature(p, R - 150, sy + 10, police.number.length * 3);
  line(p, L, sy, L + 170, sy, 0.5);
  line(p, R - 170, sy, R, sy, 0.5);
  txt(p, `${insured.display} - DNI ${insured.dni}`, L, sy - 11, { size: 7.5 });
  txt(p, `Firma ${g.del} compareciente`, L, sy - 21, { size: 7 });
  txt(p, police.signer, R - 170, sy - 11, { size: 7.5 });
  txt(p, police.station, R - 170, sy - 21, { size: 7 });
  stamp(p, R - 230, sy + 22, police.stampLines);

  txt(p, `Copia para ${g.el} denunciante - ${police.numberLabel} ${police.number} - Página 1 de 1`, PAGE_W / 2, MARGIN_FOOT,
    { size: 6.5, center: true });

  if (disclaimer) footer(p);
  return build(p, {
    title: `Acta de denuncia ${police.number}`,
    author: police.agency,
    subject: 'Denuncia policial - documento simulado de prueba',
    created: pdfDate(police.at),
  });
}

// ─── Presupuesto de servicio técnico ────────────────────────────────────────

const money = (n) => n.toLocaleString('es-AR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * A repair shop's service order with its quote: what the customer handed in and said, what the
 * technician found, the parts and labor, the validity and both signatures.
 *
 * @param shop   { name, legalName, address, phone, cuit }
 * @param repair { order, received, issued, reported, diagnosis: [], items: [[code, description, qty, unit]],
 *                 accessories, condition, technician }
 */
function presupuestoServicio({ shop, repair, device, insured, disclaimer }) {
  const p = new Page();
  const L = 42, R = PAGE_W - 42, W = R - L;

  // Shop header with the order number boxed on the right.
  txt(p, shop.name, L, 790, { font: 'F2', size: 16 });
  txt(p, 'Servicio técnico de notebooks y tablets', L, 776, { size: 8 });
  txt(p, `${shop.address} - Tel. ${shop.phone}`, L, 765, { size: 7.5 });
  txt(p, `${shop.legalName} - CUIT ${shop.cuit}`, L, 755, { size: 7.5 });
  rect(p, R - 170, 750, 170, 48);
  txt(p, 'ORDEN DE SERVICIO', R - 85, 784, { font: 'F2', size: 9, center: true });
  txt(p, `N° ${repair.order}`, R - 85, 770, { font: 'F2', size: 11, center: true });
  txt(p, `Ingreso: ${d(repair.received)} ${hm(repair.received)} hs.`, R - 85, 757, { size: 7.5, center: true });
  line(p, L, 742, R, 742, 1.2);
  txt(p, 'PRESUPUESTO DE REPARACIÓN', PAGE_W / 2, 726, { font: 'F2', size: 12, center: true });

  // Customer and device, side by side.
  const half = W / 2 - 4;
  rect(p, L, 620, half, 92, { fill: 0.96 });
  rect(p, L + half + 8, 620, half, 92, { fill: 0.96 });
  txt(p, 'CLIENTE', L + 8, 700, { font: 'F2', size: 8 });
  labeled(p, 'Nombre:', insured.display, L + 8, 686);
  labeled(p, 'DNI:', insured.dni, L + 8, 673);
  labeled(p, 'Teléfono:', insured.phone, L + 8, 660);
  wrap(insured.address, half - 60, 8).forEach((l, i) =>
    (i === 0 ? labeled(p, 'Domicilio:', l, L + 8, 647) : txt(p, l, L + 54, 647 - i * 10, { size: 8 })));
  const X2 = L + half + 16;
  txt(p, 'EQUIPO', X2, 700, { font: 'F2', size: 8 });
  labeled(p, 'Marca y modelo:', `${device.brand} ${device.model}`, X2, 686);
  labeled(p, 'Configuración:', `${device.specs}, ${device.color.toLowerCase()}`, X2, 673);
  labeled(p, 'N° de serie:', device.serial, X2, 660);
  labeled(p, 'Accesorios:', repair.accessories, X2, 647);
  labeled(p, 'Estado al ingreso:', repair.condition, X2, 634);

  // What the customer said and what the technician found.
  p.y = 600;
  p.text('FALLA DECLARADA POR EL CLIENTE', { font: 'F2', size: 8.5, x: L, leading: 12 });
  wrap(repair.reported, W, 8.6).forEach((l) => p.text(l, { size: 8.6, x: L, leading: 11 }));
  p.gap(8);
  p.text('DIAGNÓSTICO TÉCNICO', { font: 'F2', size: 8.5, x: L, leading: 12 });
  repair.diagnosis.forEach((para) => wrap(para, W, 8.6).forEach((l) => p.text(l, { size: 8.6, x: L, leading: 11 })));
  p.gap(12);

  // Parts and labor.
  const top = p.y;
  rect(p, L, top - 4, W, 18, { fill: 0.85 });
  [['Código', L + 6, false], ['Descripción', L + 86, false], ['Cant.', R - 170, true],
    ['Precio unit.', R - 90, true], ['Importe', R - 6, true]].forEach(([h, x, right]) =>
    txt(p, h, x, top + 2, { font: 'F2', size: 8, right }));
  let y = top - 18;
  let total = 0;
  repair.items.forEach(([code, desc, qty, unit]) => {
    total += qty * unit;
    txt(p, code, L + 6, y, { size: 8 });
    txt(p, desc, L + 86, y, { size: 8 });
    txt(p, String(qty), R - 170, y, { size: 8, right: true });
    txt(p, `$ ${money(unit)}`, R - 90, y, { size: 8, right: true });
    txt(p, `$ ${money(qty * unit)}`, R - 6, y, { size: 8, right: true });
    line(p, L, y - 5, R, y - 5, 0.3);
    y -= 16;
  });
  txt(p, 'TOTAL (IVA incluido)', R - 90, y - 6, { font: 'F2', size: 10, right: true });
  txt(p, `$ ${money(total)}`, R - 6, y - 6, { font: 'F2', size: 10, right: true });

  // Terms and signatures.
  p.y = y - 34;
  [
    `Presupuesto emitido el ${d(repair.issued)}. Validez: 15 días corridos. Plazo estimado de reparación: 5 días hábiles desde su aprobación.`,
    'Garantía de 90 días sobre los repuestos colocados y la mano de obra. No cubre golpes, humedad ni manipulación de terceros.',
    'Los equipos no retirados dentro de los 90 días de comunicado el presupuesto se consideran abandonados (art. 2525 CCyCN).',
    'Documento no válido como factura.',
  ].forEach((para) => wrap(para, W, 7.5).forEach((l) => p.text(l, { size: 7.5, x: L, leading: 9.5 })));

  const sy = p.y - 50;
  signature(p, L + 20, sy + 12, repair.order.length * 7);
  signature(p, R - 150, sy + 10, insured.dni.length * 5);
  line(p, L, sy, L + 170, sy, 0.5);
  line(p, R - 170, sy, R, sy, 0.5);
  txt(p, repair.technician, L, sy - 11, { size: 7.5 });
  txt(p, `Técnico - ${shop.name}`, L, sy - 21, { size: 7 });
  txt(p, `${insured.display} - DNI ${insured.dni}`, R - 170, sy - 11, { size: 7.5 });
  txt(p, 'Firma del cliente', R - 170, sy - 21, { size: 7 });

  if (disclaimer) footer(p);
  return build(p, {
    title: `Presupuesto de reparacion OS ${repair.order}`,
    author: shop.name,
    subject: 'Presupuesto por danio accidental - documento simulado de prueba',
    created: pdfDate(repair.issued),
  });
}

module.exports = { facturaB, actaDenuncia, presupuestoServicio };
