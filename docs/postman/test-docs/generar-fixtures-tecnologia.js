// Genera los 4 PDFs de la agenda documental para un caso de **Tecnología Portátil · Robo en vía
// pública**, desde un único objeto CASE, para que los datos no puedan divergir entre documentos.
//
//   police_report        denuncia_policial_tecnologia.pdf
//   purchase_proof       factura_compra_tecnologia.pdf
//   imei_deregistration  bloqueo_equipo_tecnologia.pdf
//   last_connection      ultima_conexion_tecnologia.pdf
//
// Por qué robo y no daño accidental: la agenda documental del ramo 2 hoy pide los mismos cuatro
// documentos que Celulares (init-multitenant.sql, document_requirement 5-8) y esos cuatro solo se
// sostienen juntos si hubo sustracción — una denuncia policial y una constancia de última conexión
// no existen en un daño accidental. El set se arma para la agenda que hay, sin tocarla.
//
// Los dos slots que traen nombre de celular se llenan con el documento equivalente para una
// notebook, que no tiene IMEI ni línea:
//   imei_deregistration → bloqueo remoto + registro del N° DE SERIE como equipo sustraído
//   last_connection     → último registro del servicio de localización del fabricante (Wi-Fi)
// El nombre de la parte multipart es lo único que el backend mira; el contenido es el que
// corresponde al bien. `DocumentInconsistencyEvaluator.checkImei` no participa acá porque la póliza
// del ramo no tiene IMEI (poliza.imei = NULL en el seed), así que no hay cruce que falsear.
//
// Uso: node generar-fixtures-tecnologia.js [directorio-destino]
//      Sin argumento escribe en docs/postman/test-docs/fraude/tec-portatil/

const fs = require('fs');
const path = require('path');
const { plus, d, hm, hms, iso, pdfDate, cuit, Page, letterhead, footer, build, MARGIN } = require('./lib-pdf');
const { PROFILES, variantFromArgv, outDirFromArgv, defaultOutDir } = require('./perfiles');

// Quién firma los documentos y si llevan la leyenda de simulado. `--veridica` cambia las dos cosas
// a la vez: el layout es el mismo, el caso es el mismo, cambia el firmante y el pie.
const VARIANT = variantFromArgv();
const PROFILE = PROFILES[VARIANT];
const G = PROFILE.g;

// ─────────────────────────────────────────────────────────────────────────────
// Fechas — relativas a la corrida, no fijas.
//
// Mismo motivo que en el set de Celulares: `cases.reported_at` es @CreationTimestamp y la regla D11
// compara `reportedAt - eventDate` contra el plazo de denuncia de la cobertura (96 hs en la
// cobertura 'Daño accidental' de POL-TEC-2026-311). Con fecha fija el fixture caduca en silencio.
//
// El hecho se ancla a **ayer a las 22:10**: la vuelta de una cursada nocturna. Distinto del set de
// Celulares (19:25) a propósito — dos escenarios que ocurren a la misma hora del día se confunden
// al leer los logs.
// ─────────────────────────────────────────────────────────────────────────────
const NOW = new Date();
const EVENT_AT = new Date(NOW);
EVENT_AT.setDate(EVENT_AT.getDate() - 1);
EVENT_AT.setHours(22, 10, 0, 0);

// La cadena del caso: el equipo se roba, quien denuncia lo bloquea desde el celular, el equipo se
// conecta a una red abierta minutos después, recibe el bloqueo y no vuelve a aparecer. Recién
// después va a la comisaría.
const LOCK_REQUESTED_AT = plus(EVENT_AT, 18, 5);    // +18 min — lo bloqueó desde el teléfono
const LOCK_APPLIED_AT = plus(EVENT_AT, 21, 19);     // +21 min — el equipo se conectó y recibió el comando
const POLICE_REPORT_AT = plus(EVENT_AT, 85);        // +1 h 25 min → D12 (72 hs) holgadísimo
const WIFI_SESSION_AT = plus(EVENT_AT, -23, 27);    // seguía en la red del campus antes de salir
const LAST_SYNC_AT = plus(EVENT_AT, -4, 41);
// Constancia del service emitida a la mañana siguiente (11:00), salvo que la corrida sea antes:
// un documento no puede estar fechado en el futuro respecto del momento en que se genera el set.
const SERVICE_ISSUE_AT = new Date(Math.min(plus(EVENT_AT, 770).getTime(), NOW.getTime()));

// ─────────────────────────────────────────────────────────────────────────────
// Datos del caso — única fuente de verdad para los cuatro documentos
//
// La póliza es POL-TEC-2026-311 (seed-demo.sql): Tecnología Portátil, MacBook Air M3 15", titular
// Martina Soteras. Es la única póliza del ramo que existe en la BD.
// ─────────────────────────────────────────────────────────────────────────────
const CASE = {
  insured: PROFILE.insured,
  // Sin IMEI: una notebook no tiene módem de telefonía. El identificador que cruza los cuatro
  // documentos es el número de serie, y la MAC de Wi-Fi aparece en los dos del service.
  device: {
    brand: 'APPLE',
    model: 'MacBook Air 15" (M3, 2024)',
    specs: '16 GB RAM / 512 GB SSD',
    color: 'Medianoche',
    serial: 'H7QWK3F9LM',
    mac: 'A4:83:E7:2C:91:5B',
  },
  event: {
    date: d(EVENT_AT),
    time: hm(EVENT_AT),
    iso: iso(EVENT_AT),
    place: 'Av. Medrano al 900, intersección con Av. Corrientes, barrio de Almagro, C.A.B.A.',
  },
  policeReport: {
    at: `${d(POLICE_REPORT_AT)}, ${hm(POLICE_REPORT_AT)} hs.`,
    iso: iso(POLICE_REPORT_AT),
    number: '5A-2026-009341',
    station: 'Comisaría Vecinal 5-A — Almagro',
    officer: 'Of. Ppal. Carla V. Duarte (Leg. 38.902)',
  },
  lock: {
    requestedAt: `${d(LOCK_REQUESTED_AT)}, ${hms(LOCK_REQUESTED_AT)} hs.`,
    appliedAt: `${d(LOCK_APPLIED_AT)}, ${hms(LOCK_APPLIED_AT)} hs.`,
    issuedOn: d(SERVICE_ISSUE_AT),
    number: 'BLQ-2026-0031204',
    ticket: 'ST-2026-118447',
  },
  // El último registro del equipo es el momento en que recibió el bloqueo: después no hubo más.
  lastConnection: {
    at: `${d(LOCK_APPLIED_AT)}, ${hms(LOCK_APPLIED_AT)} hs.`,
    network: 'WiFi abierta "CABA-WiFi-Publico"',
    ip: '181.44.117.203',
    area: 'Av. Corrientes al 4200, Almagro, C.A.B.A. — radio estimado 250 m',
  },
  purchase: {
    date: '26/02/2026',
    invoice: '0005-00019432',
    total: '2.100.000,00',
    net: '1.735.537,19',
    vat: '364.462,81',
    cae: '76014892355071',
    caeDue: '08/03/2026',
  },
  claimedAmount: 1980000,
  // Empresas ficticias a propósito: no queremos un comprobante que aparente ser de una empresa real.
  retailer: { name: 'Tecnodata Distribuidora S.A.', address: 'Av. Corrientes 3470, C.A.B.A.' },
  service: { name: 'TecnoService Argentina S.R.L.', address: 'Centro de Servicio Autorizado — Av. Scalabrini Ortiz 1240, C.A.B.A.' },
  policyNumber: 'POL-TEC-2026-311',
};

CASE.retailer.cuit = cuit('30', '70914628');
CASE.service.cuit = cuit('30', '68427915');

// ─────────────────────────────────────────────────────────────────────────────
// 1 · police_report — acta de denuncia
// ─────────────────────────────────────────────────────────────────────────────
function policeReport() {
  const p = new Page();
  const { insured, device, event, policeReport } = CASE;

  p.text('POLICÍA DE LA CIUDAD DE BUENOS AIRES', { font: 'F2', size: 12, center: true, leading: 13 });
  p.text('Comisaría Vecinal 5-A — Almagro', { size: 9, center: true });
  p.text('Av. Díaz Vélez 3960, Ciudad Autónoma de Buenos Aires — Tel. (011) 4958-3300', { size: 8.5, center: true });
  p.gap(4).rule().gap(8);
  p.text('ACTA DE DENUNCIA', { font: 'F2', size: 13, center: true, leading: 14 });
  p.text(`Actuación N° ${policeReport.number} — Libro de Guardia, folio 118`, { size: 9, center: true });
  p.gap(6).rule();

  p.section('DATOS DE LA ACTUACIÓN');
  p.field('Fecha y hora de recepción:', policeReport.at);
  p.field('Funcionario interviniente:', policeReport.officer);
  p.field('Carátula provisoria:', 'ROBO (art. 164 del Código Penal de la Nación)');
  p.gap(5);

  p.section(`DATOS ${G.DEL} DENUNCIANTE`);
  p.field('Apellido y nombre:', insured.formal);
  p.field('Documento:', `DNI ${insured.dni} — CUIL ${insured.cuil}`);
  p.field('Nacionalidad:', `argentin${G.a} — Fecha de nacimiento: ${insured.birth}`);
  p.field('Domicilio:', insured.address);
  p.field('Teléfono:', `${insured.phone} — Correo: ${insured.email}`);
  p.gap(5);

  p.section('DATOS DEL HECHO');
  p.field('Fecha y hora del hecho:', `${event.date}, aproximadamente ${event.time} hs.`);
  p.field('Lugar:', 'Av. Medrano al 900, intersección con Av. Corrientes,');
  p.text('             barrio de Almagro, C.A.B.A. — vía pública.', { size: 9 });
  p.gap(5);

  p.section(`RELATO ${G.DEL} DENUNCIANTE`);
  [
    `Que siendo aproximadamente las ${event.time} horas del día de la fecha, ${G.el} denunciante se`,
    'encontraba aguardando el transporte público en la parada ubicada sobre Av. Medrano al 900,',
    'de regreso de sus clases, llevando una mochila colgada del hombro derecho. Que en esas',
    `circunstancias fue sorprendid${G.a} por dos masculinos que circulaban en una motocicleta de`,
    'baja cilindrada, y que el acompañante, sin descender del rodado, le arrancó la mochila de',
    `un tirón, dándose ambos a la fuga por Av. Corrientes en dirección al oeste. Que ${G.el}`,
    'denunciante cayó al suelo por el tirón, sin sufrir lesiones que requirieran asistencia',
    `médica, y no fue amenazad${G.a} con arma alguna. Que no pudo observar el dominio del rodado`,
    'ni aportar mayores datos filiatorios de los autores. Que dentro de la mochila se',
    'encontraba su computadora portátil de uso personal y de estudio, además de material de',
    'cursada sin valor comercial. Que sobre la intersección existen cámaras del Sistema de',
    'Monitoreo Público de la Ciudad.',
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(5);

  p.section('OBJETO SUSTRAÍDO');
  p.text(`Una (1) computadora portátil marca ${device.brand}, modelo ${device.model},`, { size: 9 });
  p.text(`color ${device.color}, ${device.specs}, N° de serie ${device.serial}.`, { size: 9 });
  p.text('El equipo no posee IMEI por no contar con módem de telefonía móvil; se identifica por', { size: 9 });
  p.text('su número de serie. Una (1) mochila de tela color negro, sin valor declarado.', { size: 9 });
  p.text(`${G.El} denunciante manifiesta haber activado el bloqueo remoto del equipo desde su teléfono`, { size: 9 });
  p.text(`celular el mismo día del hecho, a las ${hm(LOCK_REQUESTED_AT)} hs.`, { size: 9 });
  p.gap(5);

  p.section('CONSTANCIAS');
  p.text('•  Se dio intervención a la Fiscalía Penal, Contravencional y de Faltas N° 12 del', { size: 9 });
  p.text('   Poder Judicial de la C.A.B.A.', { size: 9 });
  p.text('•  Se solicitó el resguardo de las imágenes de las cámaras del Sistema de Monitoreo', { size: 9 });
  p.text('   Público correspondientes a la fecha y franja horaria del hecho.', { size: 9 });
  p.text(`•  Se extiende la presente constancia ${G.al} denunciante a los fines que estime`, { size: 9 });
  p.text('   corresponder ante su compañía aseguradora.', { size: 9 });
  p.gap(12);
  p.text(`Previa lectura y ratificación, firma ${G.el} denunciante por ante el funcionario actuante.`, { size: 9 });
  p.gap(24);
  p.text('...........................................                    ...........................................', { size: 9 });
  p.text(`        ${insured.display.padEnd(15)}                                     Of. Ppal. C. V. Duarte`, { size: 8.5 });
  p.text(`        DNI ${insured.dni}                                      Comisaría Vecinal 5-A`, { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: `Acta de denuncia ${CASE.policeReport.number}`,
    author: 'Policia de la Ciudad de Buenos Aires',
    subject: 'Robo en via publica de equipo portatil - documento simulado de prueba',
    created: pdfDate(POLICE_REPORT_AT),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 2 · purchase_proof — factura de compra del equipo
// ─────────────────────────────────────────────────────────────────────────────
function purchaseProof() {
  const p = new Page();
  const { insured, device, purchase, retailer } = CASE;

  letterhead(p, retailer.name, `${retailer.address} — Tel. (011) 4864-7700`, retailer.cuit);

  p.text('FACTURA  B', { font: 'F2', size: 14, center: true, leading: 15 });
  p.text('Documento no válido como crédito fiscal — Consumidor Final', { size: 8, center: true });
  p.gap(8);

  p.field('Comprobante N°:', purchase.invoice);
  p.field('Fecha de emisión:', purchase.date);
  p.field('Condición frente al IVA:', 'Responsable Inscripto');
  p.field('Ingresos Brutos:', '901-914628-3    Inicio de actividades: 07/2008');
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
  p.text(`  1     Notebook ${device.brand} ${device.model} — ${device.specs}`, { size: 9 });
  p.text(`        Color ${device.color} — teclado español latinoamericano`, { size: 9 });
  p.text(`        N° de serie: ${device.serial} — Garantía oficial 12 meses`, { size: 9 });
  p.gap(8);

  p.moneyRow('Subtotal (neto gravado)', `$ ${purchase.net}`);
  p.moneyRow('IVA 21%', `$ ${purchase.vat}`);
  p.gap(2);
  p.moneyRow('TOTAL', `$ ${purchase.total}`, { size: 11, bold: true, leading: 16 });

  p.rule();
  p.field('Forma de pago:', 'Tarjeta de crédito — 12 cuotas sin interés');
  p.field('CAE N°:', purchase.cae);
  p.field('Vencimiento del CAE:', purchase.caeDue);
  p.gap(10);

  p.text('El presente comprobante acredita la titularidad del equipo detallado. Conservar para', { size: 8.5 });
  p.text('gestiones de garantía o ante la compañía aseguradora.', { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: `Factura B ${purchase.invoice}`,
    author: retailer.name,
    subject: 'Comprobante de compra del equipo portatil - documento simulado de prueba',
    created: "20260226164500-03'00'",
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 3 · imei_deregistration — constancia de bloqueo remoto y registro del equipo
//
// El equivalente para una notebook de la baja de IMEI: el equipo no se da de baja de una red móvil
// —no está en ninguna—, se bloquea contra la cuenta del fabricante y su número de serie queda
// registrado como sustraído. Lo que aporta al expediente es lo mismo: el bien quedó inutilizable.
// ─────────────────────────────────────────────────────────────────────────────
function deviceLock() {
  const p = new Page();
  const { insured, device, lock, policeReport, service } = CASE;

  letterhead(p, service.name, `${service.address} — Tel. (011) 4832-5500`, service.cuit);

  p.text('CONSTANCIA DE BLOQUEO REMOTO Y REGISTRO DE EQUIPO SUSTRAÍDO', { font: 'F2', size: 11.5, center: true, leading: 14 });
  p.text('Servicio Técnico Autorizado — Gestión de equipos denunciados', { size: 8.5, center: true });
  p.gap(6).rule();

  p.field('Constancia N°:', lock.number);
  p.field('Orden de gestión N°:', lock.ticket);
  p.field('Fecha de emisión:', lock.issuedOn);
  p.gap(6);

  p.section('DATOS DEL TITULAR');
  p.field('Apellido y nombre:', insured.formal);
  p.field('Documento:', `DNI ${insured.dni}`);
  p.field('Cuenta asociada:', insured.email);
  p.field('Teléfono de contacto:', insured.phone);
  p.gap(6);

  p.section('DATOS DEL EQUIPO');
  p.field('Marca y modelo:', `${device.brand} ${device.model}`);
  p.field('Configuración:', `${device.specs} — ${device.color}`);
  p.field('N° de serie:', device.serial);
  p.field('Dirección MAC (Wi-Fi):', device.mac);
  p.field('IMEI:', 'no aplica — el equipo no posee módem de telefonía móvil');
  p.gap(6);

  p.section('DATOS DE LA GESTIÓN');
  p.field('Motivo:', 'Robo del equipo en la vía pública');
  p.field('Bloqueo solicitado:', lock.requestedAt);
  p.field('Canal:', 'Aplicación de localización del fabricante, desde dispositivo asociado');
  p.field('Bloqueo aplicado:', lock.appliedAt);
  p.field('Actuación policial:', `N° ${policeReport.number}, ${policeReport.station}`);
  p.gap(8);

  p.box(30);
  p.text('ESTADO ACTUAL DEL EQUIPO:  BLOQUEADO', { font: 'F2', size: 10, x: MARGIN + 12, leading: 12 });
  p.text(`N° de serie ${device.serial} registrado como sustraído en la base del fabricante.`,
    { size: 8.5, x: MARGIN + 12 });
  p.gap(14);

  p.section('ALCANCE DEL BLOQUEO');
  [
    'A partir de la fecha y hora de aplicación, el equipo identificado con el número de serie',
    'consignado queda bloqueado contra la cuenta de su titular: no puede desbloquearse, borrarse,',
    `reinstalarse ni asociarse a otra cuenta sin las credenciales ${G.del} denunciante. El registro`,
    'del número de serie impide además su ingreso a la red de servicio técnico autorizado para',
    'reparación o venta de partes.',
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(8);
  [
    `Se deja constancia de que el bloqueo fue solicitado ${G.por} titular con anterioridad a la`,
    'radicación de la denuncia policial, y que el equipo lo recibió y aplicó al reconectarse a',
    'una red inalámbrica (ver constancia de última conexión registrada).',
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(10);

  p.text(`La presente se extiende a pedido ${G.del} titular para ser presentada ante su compañía`, { size: 8.5 });
  p.text('aseguradora.', { size: 8.5 });
  p.gap(22);
  p.text('...........................................', { size: 9 });
  p.text('        Mesa de Gestiones', { size: 8.5 });
  p.text(`        ${service.name}`, { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: `Constancia de bloqueo remoto ${lock.number}`,
    author: service.name,
    subject: 'Bloqueo de equipo portatil por robo - documento simulado de prueba',
    created: pdfDate(SERVICE_ISSUE_AT),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 4 · last_connection — último registro del servicio de localización
// ─────────────────────────────────────────────────────────────────────────────
function lastConnection() {
  const p = new Page();
  const { insured, device, lastConnection: lc, lock, service } = CASE;

  letterhead(p, service.name, `${service.address} — Área Técnica / Servicio de localización`, service.cuit);

  p.text('INFORME DE ÚLTIMA CONEXIÓN REGISTRADA', { font: 'F2', size: 12.5, center: true, leading: 14 });
  p.gap(6).rule();

  p.field('Informe N°:', 'REG-2026-0031205');
  p.field('Fecha de emisión:', lock.issuedOn);
  p.field('Solicitado por:', `${insured.display}, DNI ${insured.dni}`);
  p.gap(6);

  p.section('EQUIPO CONSULTADO');
  p.field('Marca y modelo:', `${device.brand} ${device.model}`);
  p.field('N° de serie:', device.serial);
  p.field('Dirección MAC (Wi-Fi):', device.mac);
  p.field('Cuenta asociada:', insured.email);
  p.gap(6);

  p.section('ÚLTIMOS REGISTROS DEL SERVICIO');
  p.gap(2);
  p.text('Fecha y hora                Evento                            Red / origen', { font: 'F2', size: 8.5, leading: 12 });
  // Segundos "sucios" a propósito: un log real no cae en :00 redondo.
  [
    [WIFI_SESSION_AT, 'Conexión a red inalámbrica ', 'Campus-Alumnos (WPA2)'],
    [LAST_SYNC_AT, 'Sincronización de datos    ', 'Campus-Alumnos (WPA2)'],
    [LOCK_REQUESTED_AT, 'Bloqueo remoto solicitado  ', 'dispositivo asociado — pendiente'],
    [LOCK_APPLIED_AT, 'Bloqueo aplicado / última  ', `${lc.ip} — red abierta`],
  ].forEach(([when, event, origin]) =>
    p.text(`${d(when)}  ${hms(when)}        ${event}    ${origin}`, { size: 8.5 }));
  p.gap(10);

  p.section('RESULTADO DE LA CONSULTA');
  p.field('Último registro:', lc.at);
  p.field('Red utilizada:', lc.network);
  p.field('Dirección IP pública:', lc.ip);
  p.field('Ubicación aproximada:', 'Av. Corrientes al 4200, Almagro, C.A.B.A.');
  p.field('Precisión estimada:', 'radio de 250 m (posicionamiento por red, sin GPS)');
  p.field('Registros posteriores:', 'ninguno');
  p.field('Estado del equipo:', `bloqueado (ver constancia ${lock.number})`);
  p.gap(10);

  [
    'No se registran conexiones del equipo consultado al servicio de localización con',
    'posterioridad a la fecha y hora indicadas. El último registro corresponde al momento en',
    'que el equipo se conectó a una red inalámbrica abierta y recibió el comando de bloqueo',
    'solicitado por su titular, quedando inutilizable a partir de ese instante.',
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(8);
  [
    'La ubicación informada se estima a partir de la dirección IP y de las redes inalámbricas',
    'detectadas, y no constituye una posición exacta del equipo.',
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(22);

  p.text('...........................................', { size: 9 });
  p.text('        Área Técnica — Servicio de localización', { size: 8.5 });
  p.text(`        ${service.name}`, { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: 'Informe de ultima conexion registrada REG-2026-0031205',
    author: service.name,
    subject: 'Ultima conexion del equipo portatil - documento simulado de prueba',
    created: pdfDate(SERVICE_ISSUE_AT),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// Destino por defecto: la carpeta del escenario, junto al resto de sus fixtures. Se puede pisar
// pasando un directorio (sirve para generar en un temporal y comparar antes de reemplazar).
const outDir = outDirFromArgv() || defaultOutDir(__dirname, VARIANT, 'tec-portatil');
fs.mkdirSync(outDir, { recursive: true });
console.log(`Destino: ${outDir}
`);

const documents = [
  ['denuncia_policial_tecnologia.pdf', policeReport(), 'police_report'],
  ['factura_compra_tecnologia.pdf', purchaseProof(), 'purchase_proof'],
  ['bloqueo_equipo_tecnologia.pdf', deviceLock(), 'imei_deregistration'],
  ['ultima_conexion_tecnologia.pdf', lastConnection(), 'last_connection'],
];

for (const [name, bytes, type] of documents) {
  fs.writeFileSync(path.join(outDir, name), bytes);
  console.log(`${type.padEnd(20)} ${name.padEnd(38)} ${bytes.length} bytes`);
}

// El payload sale del mismo CASE que los PDFs: si las fechas del expediente y las de los
// documentos se separan, el caso deja de ser coherente y nadie se entera hasta leerlos.
const payload = {
  branch: 'Tecnología Portátil',
  product: 'Seguro de Tecnología Portátil',
  claimCause: 'Robo en vía pública',
  insuredItem: 'Apple MacBook Air 15" M3 512 GB',
  insuredId: CASE.insured.dni,
  policyNumber: CASE.policyNumber,
  description:
    'Volvía de cursar y esperaba el colectivo en Av. Medrano y Av. Corrientes con la mochila al '
    + 'hombro. Dos personas en moto me arrancaron la mochila de un tirón y escaparon por Av. '
    + 'Corrientes; adentro llevaba mi notebook. Bloqueé el equipo en forma remota esa misma noche '
    + 'desde el celular y radiqué la denuncia en la Comisaría Vecinal 5-A de Almagro.',
  eventDate: CASE.event.iso,
  eventLocation: 'Av. Medrano 900, Almagro, C.A.B.A.',
  policeReportAt: CASE.policeReport.iso,
  claimedAmount: CASE.claimedAmount,
  pep: false,
  imageConsent: true,
  contactEmail: CASE.insured.email,
  contactPhone: CASE.insured.phone,
};
fs.writeFileSync(path.join(outDir, 'caso_tecnologia.json'), JSON.stringify(payload, null, 2) + '\n');
console.log(`${'case (payload)'.padEnd(20)} ${'caso_tecnologia.json'.padEnd(38)} eventDate=${CASE.event.iso}`);

const expiresAt = new Date(EVENT_AT.getTime() + 96 * 3600_000);
const hoursAgo = Math.round((NOW - EVENT_AT) / 3600_000);
console.log(`\nCUIT comercio: ${CASE.retailer.cuit}   CUIT service: ${CASE.service.cuit}`);
console.log(`Hecho: ${CASE.event.date} ${CASE.event.time} (hace ${hoursAgo} h)`);
console.log(`Reclamado $${CASE.claimedAmount.toLocaleString('es-AR')} contra una factura de $${CASE.purchase.total}`);
console.log('(dentro del 10% que tolera DocumentInconsistencyEvaluator.checkAmount).');
console.log(`El set vence el ${d(expiresAt)} ${hm(expiresAt)} — después D11 (plazo de denuncia, 96 hs`);
console.log('de la cobertura del ramo) bloquea el Fast Track. Volvé a correr este script para renovarlo.');
