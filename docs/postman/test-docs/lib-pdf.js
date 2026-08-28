// Motor PDF mínimo y helpers de fecha compartidos por los generadores de fixtures.
//
// Escribe PDF 1.4 a mano, sin dependencias: Helvetica + WinAnsiEncoding, una página, texto
// seleccionable. Una sola página por documento no es un capricho: OllamaDocumentAnalyzer rasteriza
// el PDF a 150 DPI y manda cada página al modelo de visión, así que un PDF de 5 páginas son 5
// inferencias.
//
// Lo usan generar-fixtures.js (Celulares) y generar-fixtures-tecnologia.js (Tecnología Portátil).

const pad = (n) => String(n).padStart(2, '0');

/** Suma minutos (y segundos) a una fecha, sin mutarla. */
const plus = (base, minutes, seconds = 0) => new Date(base.getTime() + minutes * 60_000 + seconds * 1000);

/** dd/mm/aaaa */
const d = (x) => `${pad(x.getDate())}/${pad(x.getMonth() + 1)}/${x.getFullYear()}`;
const hm = (x) => `${pad(x.getHours())}:${pad(x.getMinutes())}`;
const hms = (x) => `${hm(x)}:${pad(x.getSeconds())}`;

/** El formato que espera el backend en eventDate / policeReportAt. */
const iso = (x) => `${x.getFullYear()}-${pad(x.getMonth() + 1)}-${pad(x.getDate())}T${hm(x)}:00`;

/** Formato /Info de PDF: D:YYYYMMDDHHmmSS-03'00' */
const pdfDate = (x) =>
  `${x.getFullYear()}${pad(x.getMonth() + 1)}${pad(x.getDate())}${pad(x.getHours())}${pad(x.getMinutes())}00-03'00'`;

/** Dígito verificador de CUIT, para que los números no canten falso si alguien los valida. */
function cuit(prefix, body) {
  const weights = [5, 4, 3, 2, 7, 6, 5, 4, 3, 2];
  const digits = (prefix + body).split('').map(Number);
  const sum = digits.reduce((acc, digit, i) => acc + digit * weights[i], 0);
  const rest = 11 - (sum % 11);
  const check = rest === 11 ? 0 : rest === 10 ? 9 : rest;
  return `${prefix}-${body}-${check}`;
}

// ─────────────────────────────────────────────────────────────────────────────
// Motor PDF
// ─────────────────────────────────────────────────────────────────────────────
const WINANSI = { '€':0x80,'‚':0x82,'ƒ':0x83,'„':0x84,'…':0x85,'†':0x86,'‡':0x87,'ˆ':0x88,'‰':0x89,'Š':0x8a,'‹':0x8b,'Œ':0x8c,'Ž':0x8e,'‘':0x91,'’':0x92,'“':0x93,'”':0x94,'•':0x95,'–':0x96,'—':0x97,'˜':0x98,'™':0x99,'š':0x9a,'›':0x9b,'œ':0x9c,'ž':0x9e,'Ÿ':0x9f };

function esc(str) {
  let out = '';
  for (const ch of str) {
    const cp = ch.codePointAt(0);
    let byte;
    if (WINANSI[ch] !== undefined) byte = WINANSI[ch];
    else if (cp < 0x100) byte = cp;
    else throw new Error(`Carácter fuera de WinAnsi: ${ch} (U+${cp.toString(16)})`);

    if (ch === '(' || ch === ')' || ch === '\\') out += '\\' + ch;
    else if (byte < 0x20 || byte > 0x7e) out += '\\' + byte.toString(8).padStart(3, '0');
    else out += ch;
  }
  return out;
}

const AVG = { F1: 0.50, F2: 0.55 };
const PAGE_W = 595.276, PAGE_H = 841.890;
const MARGIN = 56;

class Page {
  constructor() { this.ops = []; this.y = PAGE_H - MARGIN; }

  text(str, { font = 'F1', size = 9, x = MARGIN, center = false, leading = 10.5, right = null } = {}) {
    let px = x;
    if (center) px = (PAGE_W - str.length * size * AVG[font]) / 2;
    if (right !== null) px = right - str.length * size * AVG[font];
    this.ops.push(`BT /${font} ${size} Tf ${px.toFixed(2)} ${this.y.toFixed(2)} Td (${esc(str)}) Tj ET`);
    if (right === null) this.y -= leading;
    return this;
  }

  /** Etiqueta en negrita + valor, alineados en columna. */
  field(label, value, { labelWidth = 150, size = 9 } = {}) {
    this.ops.push(`BT /F2 ${size} Tf ${MARGIN} ${this.y.toFixed(2)} Td (${esc(label)}) Tj ET`);
    this.ops.push(`BT /F1 ${size} Tf ${(MARGIN + labelWidth).toFixed(2)} ${this.y.toFixed(2)} Td (${esc(value)}) Tj ET`);
    this.y -= 11;
    return this;
  }

  /** Etiqueta a la izquierda + importe alineado a la derecha, en la MISMA línea. */
  moneyRow(label, amount, { size = 9, bold = false, leading = 12 } = {}) {
    const font = bold ? 'F2' : 'F1';
    const amountX = PAGE_W - MARGIN - amount.length * size * AVG[font];
    this.ops.push(`BT /${font} ${size} Tf ${MARGIN} ${this.y.toFixed(2)} Td (${esc(label)}) Tj ET`);
    this.ops.push(`BT /${font} ${size} Tf ${amountX.toFixed(2)} ${this.y.toFixed(2)} Td (${esc(amount)}) Tj ET`);
    this.y -= leading;
    return this;
  }

  section(title) { return this.text(title, { font: 'F2', size: 9.5, leading: 12.5 }); }
  gap(n = 6) { this.y -= n; return this; }

  rule(width = 0.6) {
    this.ops.push(`${width} w ${MARGIN} ${this.y.toFixed(2)} m ${(PAGE_W - MARGIN).toFixed(2)} ${this.y.toFixed(2)} l S`);
    this.y -= 8;
    return this;
  }

  box(height) {
    this.ops.push(`0.6 w ${MARGIN} ${(this.y - height + 8).toFixed(2)} ${(PAGE_W - 2 * MARGIN).toFixed(2)} ${height.toFixed(2)} re S`);
    return this;
  }

  at(y) { this.y = y; return this; }
  stream() { return this.ops.join('\n'); }
}

function letterhead(p, org, address, cuitNumber) {
  p.text(org, { font: 'F2', size: 12, center: true, leading: 13.5 });
  p.text(address, { size: 8.5, center: true });
  if (cuitNumber) p.text(`CUIT ${cuitNumber}`, { size: 8.5, center: true });
  p.gap(4).rule().gap(8);
  return p;
}

function footer(p) {
  p.at(MARGIN + 14).rule(0.4);
  p.text('Documento simulado con fines de prueba — sistema Arbiter (UTN FRBA, DDSI K5054, grupo 5303).',
    { size: 7, center: true, leading: 8 });
  p.text('No constituye un comprobante ni una constancia real. Personas, empresas y números, ficticios.',
    { size: 7, center: true, leading: 8 });
  return p;
}

function build(page, meta) {
  const content = page.stream();
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${PAGE_W} ${PAGE_H}] /Resources << /Font << /F1 5 0 R /F2 6 0 R >> /ProcSet [/PDF /Text] >> /Contents 4 0 R >>`,
    `<< /Length ${Buffer.byteLength(content, 'latin1')} >>\nstream\n${content}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>',
    `<< /Title (${esc(meta.title)}) /Author (${esc(meta.author)}) /Subject (${esc(meta.subject)}) /Keywords (fixture de prueba, sistema Arbiter, documento generado, no es un documento real) /Producer (Arbiter test fixture) /CreationDate (D:${meta.created}) >>`,
  ];

  let pdf = '%PDF-1.4\n%\xe2\xe3\xcf\xd3\n';
  const offsets = [];
  objects.forEach((body, i) => {
    offsets.push(Buffer.byteLength(pdf, 'latin1'));
    pdf += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xrefPos = Buffer.byteLength(pdf, 'latin1');
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  offsets.forEach((o) => { pdf += `${String(o).padStart(10, '0')} 00000 n \n`; });
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R /Info 7 0 R >>\nstartxref\n${xrefPos}\n%%EOF\n`;
  return Buffer.from(pdf, 'latin1');
}

module.exports = { pad, plus, d, hm, hms, iso, pdfDate, cuit, esc, Page, letterhead, footer, build, MARGIN, PAGE_W, PAGE_H };
