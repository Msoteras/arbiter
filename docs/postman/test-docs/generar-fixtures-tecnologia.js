// Genera los fixtures del ramo **Tecnología Portátil** — un juego por hecho generador, siguiendo la
// agenda documental que el seed le pide a cada uno
// (db/init-multitenant.sql, document_requirement 15-23):
//
//   robo/       police_report · purchase_proof · item_photo (*)   [+ bloqueo y última conexión]
//   hurto/      police_report · purchase_proof · item_photo (*)   [+ bloqueo]
//   danio_acc/  purchase_proof · repair_quote · item_photo (*)
//
// (*) `item_photo` no se genera —es una imagen, no un PDF—, pero ya existe:
//     `foto_notebook_para_fraude.jpg`, en la raíz de test-docs. Un MacBook Air Medianoche cerrado
//     sobre una mesa, el mismo color que declara la póliza. Se adjunta desde ahí y no se copia
//     dentro de cada caso, para no repetir el archivo seis veces.
//
// Entre corchetes, documentos que la agenda ya no exige pero que el relato produce igual: siguen
// siendo adjuntos válidos y el LLM los lee cuando el caso no fast-trackea.
//
// El bien no tiene IMEI ni línea: una notebook se identifica por número de serie y MAC de Wi-Fi, y
// se inutiliza bloqueándola contra la cuenta del fabricante. Por eso el slot `imei_deregistration`
// viaja con una constancia de bloqueo remoto — el backend solo mira el nombre de la parte multipart.
// `DocumentInconsistencyEvaluator.checkImei` no participa acá porque la póliza del ramo no tiene
// IMEI (poliza.imei = NULL en el seed), así que no hay cruce que falsear.
//
// Uso: node generar-fixtures-tecnologia.js [directorio-destino] [--sin-marca]
//      Sin argumentos escribe en docs/postman/test-docs/conMarcaDePrueba/tec-portatil/<hecho>/

const fs = require('fs');
const path = require('path');
const { plus, d, hm, hms, iso, pdfDate, cuit, Page, letterhead, footer, build, MARGIN } = require('./lib-pdf');
const { PROFILES, variantFromArgv, outDirFromArgv } = require('./perfiles');

const VARIANT = variantFromArgv();
const PROFILE = PROFILES[VARIANT];
const G = PROFILE.g;
const INSURED = PROFILE.insured;

// ─────────────────────────────────────────────────────────────────────────────
// Fechas — relativas a la corrida, no fijas. Mismo motivo que en Celulares: la regla D11 compara
// `reportedAt - eventDate` contra el plazo de denuncia de la cobertura (96 hs en la cobertura
// 'Daño accidental' de POL-TEC-2026-311). Con fecha fija el fixture caduca en silencio.
//
// Cada hecho tiene su hora: el robo a la vuelta de una cursada nocturna, el hurto a media tarde en
// una sala de lectura, el daño a la mañana en el escritorio.
// ─────────────────────────────────────────────────────────────────────────────
const NOW = new Date();

function ayerA(hora, minuto) {
  const x = new Date(NOW);
  x.setDate(x.getDate() - 1);
  x.setHours(hora, minuto, 0, 0);
  return x;
}

function emitidaLaMananaSiguiente(evento, minutos) {
  return new Date(Math.min(plus(evento, minutos).getTime(), NOW.getTime()));
}

// ─────────────────────────────────────────────────────────────────────────────
// Constantes del ramo
//
// La póliza es POL-TEC-2026-311 (seed-demo.sql): Tecnología Portátil, MacBook Air M3 15".
// Es la única póliza del ramo que existe en la BD.
// ─────────────────────────────────────────────────────────────────────────────
const BRANCH = 'Tecnología Portátil';
const PRODUCT = 'Seguro de Tecnología Portátil';
const POLICY_NUMBER = 'POL-TEC-2026-311';

const DEVICE = {
  brand: 'APPLE',
  model: 'MacBook Air 15" (M3, 2024)',
  specs: '16 GB RAM / 512 GB SSD',
  color: 'Medianoche',
  serial: 'H7QWK3F9LM',
  mac: 'A4:83:E7:2C:91:5B',
};

const PURCHASE = {
  date: '26/02/2026',
  invoice: '0005-00019432',
  total: '2.100.000,00',
  net: '1.735.537,19',
  vat: '364.462,81',
  cae: '76014892355071',
  caeDue: '08/03/2026',
};

// Empresas ficticias a propósito: no queremos comprobantes que aparenten ser de una empresa real.
const RETAILER = { name: 'Tecnodata Distribuidora S.A.', address: 'Av. Corrientes 3470, C.A.B.A.' };
const SERVICE = {
  name: 'TecnoService Argentina S.R.L.',
  address: 'Centro de Servicio Autorizado — Av. Scalabrini Ortiz 1240, C.A.B.A.',
};

RETAILER.cuit = cuit('30', '70914628');
SERVICE.cuit = cuit('30', '68427915');

// ─────────────────────────────────────────────────────────────────────────────
// Los tres escenarios
// ─────────────────────────────────────────────────────────────────────────────

// ── Robo en vía pública ──────────────────────────────────────────────────────
const ROBO_EVENT = ayerA(22, 10);
const ROBO = {
  folder: 'robo',
  claimCause: 'Robo en vía pública',
  documents: ['police_report', 'purchase_proof', 'imei_deregistration', 'last_connection'],
  event: ROBO_EVENT,
  claimedAmount: 1980000,
  eventLocation: 'Av. Medrano 900, en la parada de colectivo de la esquina con Av. Corrientes',
  locality: 'Almagro',
  province: 'Ciudad Autónoma de Buenos Aires',
  description:
    'Volvía de cursar y esperaba el colectivo en Av. Medrano y Av. Corrientes con la mochila al '
    + 'hombro. Dos personas en moto me arrancaron la mochila de un tirón y escaparon por Av. '
    + 'Corrientes; adentro llevaba mi notebook. Bloqueé el equipo en forma remota esa misma noche '
    + 'desde el celular y radiqué la denuncia en la Comisaría Vecinal 5-A de Almagro.',
  police: {
    at: plus(ROBO_EVENT, 85),           // +1 h 25 min → D12 (72 hs) holgadísimo
    number: '5A-2026-009341',
    folio: '118',
    station: 'Comisaría Vecinal 5-A — Almagro',
    stationAddress: 'Av. Díaz Vélez 3960, Ciudad Autónoma de Buenos Aires — Tel. (011) 4958-3300',
    officer: 'Of. Ppal. Carla V. Duarte (Leg. 38.902)',
    officerShort: 'Of. Ppal. C. V. Duarte',
    caratula: 'ROBO (art. 164 del Código Penal de la Nación)',
    fiscalia: 'N° 12',
    place: [
      'Av. Medrano al 900, intersección con Av. Corrientes,',
      '             barrio de Almagro, C.A.B.A. — vía pública.',
    ],
    relato: (hora) => [
      `Que siendo aproximadamente las ${hora} horas del día de la fecha, ${G.el} denunciante se`,
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
    ],
    camaras: true,
  },
  lock: {
    requested: plus(ROBO_EVENT, 18, 5),  // lo bloqueó desde el teléfono
    applied: plus(ROBO_EVENT, 21, 19),   // el equipo se conectó y recibió el comando
    number: 'BLQ-2026-0031204',
    ticket: 'ST-2026-118447',
    canal: 'Aplicación de localización del fabricante, desde dispositivo asociado',
    estado: 'BLOQUEADO',
    aplicado: true,
  },
  lastConnection: {
    number: 'REG-2026-0031205',
    network: 'WiFi abierta "CABA-WiFi-Publico"',
    ip: '181.44.117.203',
    area: 'Av. Corrientes al 4200, Almagro, C.A.B.A.',
    registros: [
      [-23, 27, 'Conexión a red inalámbrica ', 'Campus-Alumnos (WPA2)'],
      [-4, 41, 'Sincronización de datos    ', 'Campus-Alumnos (WPA2)'],
      [18, 5, 'Bloqueo remoto solicitado  ', 'dispositivo asociado — pendiente'],
      [21, 19, 'Bloqueo aplicado / última  ', '181.44.117.203 — red abierta'],
    ],
  },
};

// ── Hurto ────────────────────────────────────────────────────────────────────
// Sin violencia (art. 162) y sin que la víctima lo advierta en el momento: la mochila desaparece de
// la silla mientras estudia. La diferencia técnica con el robo se ve en el bloqueo: el equipo nunca
// se reconectó, así que el comando quedó PENDIENTE y no hay constancia de última conexión que valga.
const HURTO_EVENT = ayerA(16, 20);
const HURTO = {
  folder: 'hurto',
  claimCause: 'Hurto',
  documents: ['police_report', 'purchase_proof', 'imei_deregistration'],
  event: HURTO_EVENT,
  claimedAmount: 1980000,
  eventLocation: 'Av. Díaz Vélez 4200, sala de lectura de la biblioteca popular',
  locality: 'Almagro',
  province: 'Ciudad Autónoma de Buenos Aires',
  description:
    'Estaba estudiando en la sala de lectura de la biblioteca con la mochila colgada del respaldo '
    + 'de la silla. Fui hasta el mostrador a devolver un libro y cuando volví la mochila no estaba, '
    + 'con la notebook adentro. Nadie vio nada. Denuncié esa tarde y pedí el bloqueo del equipo, '
    + 'pero como no se volvió a conectar quedó pendiente de aplicarse.',
  police: {
    at: plus(HURTO_EVENT, 125),         // +2 h 05 min: primero lo buscó en la biblioteca
    number: '5A-2026-009518',
    folio: '143',
    station: 'Comisaría Vecinal 5-A — Almagro',
    stationAddress: 'Av. Díaz Vélez 3960, Ciudad Autónoma de Buenos Aires — Tel. (011) 4958-3300',
    officer: 'Of. Sub. Damián E. Rearte (Leg. 42.660)',
    officerShort: 'Of. Sub. D. E. Rearte',
    caratula: 'HURTO (art. 162 del Código Penal de la Nación)',
    fiscalia: 'N° 12',
    place: [
      'Av. Díaz Vélez al 4200, sala de lectura de biblioteca popular,',
      '             barrio de Almagro, C.A.B.A. — lugar de acceso público.',
    ],
    relato: (hora) => [
      `Que siendo aproximadamente las ${hora} horas del día de la fecha, ${G.el} denunciante se`,
      'encontraba estudiando en la sala de lectura de la biblioteca sita en el domicilio',
      'consignado, con su mochila colgada del respaldo de la silla que ocupaba. Que se apartó de',
      'su lugar por un lapso que estima en cinco a diez minutos para devolver material en el',
      `mostrador de préstamos, y que al regresar advirtió la ausencia de la mochila. Que ${G.el}`,
      `denunciante no fue abordad${G.a}, amenazad${G.a} ni tuvo contacto alguno con persona alguna,`,
      'y no puede sindicar autor ni aportar descripción. Que consultó al personal de la',
      'institución y a las personas presentes en la sala, sin obtener resultado. Que dentro de la',
      'mochila se encontraba su computadora portátil de uso personal y de estudio, junto con',
      'apuntes sin valor comercial. Que la sala de lectura no cuenta con cámaras de seguridad,',
      'sí el hall de ingreso al edificio.',
    ],
    camaras: false,
  },
  lock: {
    requested: plus(HURTO_EVENT, 170),
    applied: null,                      // nunca se reconectó: el comando quedó en cola
    number: 'BLQ-2026-0031388',
    ticket: 'ST-2026-118692',
    canal: 'Autogestión web del fabricante — sesión validada con clave',
    estado: 'BLOQUEO PENDIENTE DE APLICACIÓN',
    aplicado: false,
  },
};

// ── Daño accidental ──────────────────────────────────────────────────────────
// El hecho generador propio del ramo: sin denuncia policial y con el presupuesto de reparación como
// documento central. El monto reclamado ya no es el valor del equipo sino el costo de arreglarlo.
const DANIO_EVENT = ayerA(10, 15);
const DANIO = {
  folder: 'danio_acc',
  claimCause: 'Daño accidental',
  documents: ['purchase_proof', 'repair_quote'],
  event: DANIO_EVENT,
  claimedAmount: 757000,
  eventLocation: 'Av. Warnes 1470, piso 2° "A" — domicilio particular, escritorio de trabajo',
  locality: 'La Paternal',
  province: 'Ciudad Autónoma de Buenos Aires',
  description:
    'Estaba trabajando en el escritorio con la notebook abierta y volqué la taza de café sobre el '
    + 'teclado. La apagué y la puse boca abajo, pero cuando quise encenderla más tarde ya no dio '
    + 'señal. La llevé al servicio técnico autorizado y me presupuestaron el cambio del teclado y '
    + 'la parte superior del equipo.',
  repair: {
    number: 'PRE-2026-002289',
    order: 'ST-2026-118904',
    received: plus(DANIO_EVENT, 320),
    issued: plus(DANIO_EVENT, 1580),
    diagnostico: [
      'Se recibe el equipo apagado, sin respuesta al botón de encendido ni indicación de carga.',
      'Al abrirlo se constata residuo azucarado seco distribuido bajo el conjunto de teclado y en',
      'el alojamiento de la batería, con corrosión incipiente en el conector del trackpad. Los',
      'indicadores de contacto con líquido del chasis superior están activados. La placa lógica',
      'responde en banco de pruebas con teclado externo: el daño se concentra en el top case.',
    ],
    items: [
      ['Top case original con teclado, trackpad y batería — repuesto', '612.000,00'],
      ['Mano de obra especializada: desarmado, limpieza y reemplazo', '145.000,00'],
    ],
    total: '757.000,00',
    observaciones: [
      'El diagnóstico se realiza sobre el estado actual del equipo. El daño por líquido puede',
      'manifestar fallas posteriores en componentes no alcanzados por este presupuesto. El equipo',
      'permanece en el taller a la espera de conformidad; no se inició la reparación. Presupuesto',
      'sin cargo, válido por 15 días corridos. Repuestos originales con 12 meses de garantía.',
    ],
  },
};

const SCENARIOS = [ROBO, HURTO, DANIO];

// ─────────────────────────────────────────────────────────────────────────────
// 1 · police_report — acta de denuncia
// ─────────────────────────────────────────────────────────────────────────────
function policeReport(sc) {
  const p = new Page();
  const { police, lock } = sc;

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
  p.text(`Una (1) computadora portátil marca ${DEVICE.brand}, modelo ${DEVICE.model},`, { size: 9 });
  p.text(`color ${DEVICE.color}, ${DEVICE.specs}, N° de serie ${DEVICE.serial}.`, { size: 9 });
  p.text('El equipo no posee IMEI por no contar con módem de telefonía móvil; se identifica por', { size: 9 });
  p.text('su número de serie. Una (1) mochila de tela color negro, sin valor declarado.', { size: 9 });
  p.text(`${G.El} denunciante manifiesta haber solicitado el bloqueo remoto del equipo el mismo`, { size: 9 });
  p.text(`día del hecho, a las ${hm(lock.requested)} hs.`, { size: 9 });
  p.gap(5);

  p.section('CONSTANCIAS');
  p.text(`•  Se dio intervención a la Fiscalía Penal, Contravencional y de Faltas ${police.fiscalia} del`, { size: 9 });
  p.text('   Poder Judicial de la C.A.B.A.', { size: 9 });
  if (police.camaras) {
    p.text('•  Se solicitó el resguardo de las imágenes de las cámaras del Sistema de Monitoreo', { size: 9 });
    p.text('   Público correspondientes a la fecha y franja horaria del hecho.', { size: 9 });
  } else {
    p.text('•  Se libró oficio a la institución a fin de requerir el resguardo de las imágenes del', { size: 9 });
    p.text('   hall de ingreso correspondientes a la fecha y franja horaria del hecho.', { size: 9 });
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
    subject: `${sc.claimCause} de equipo portatil - documento simulado de prueba`,
    created: pdfDate(police.at),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 2 · purchase_proof — factura de compra del equipo
// ─────────────────────────────────────────────────────────────────────────────
function purchaseProof() {
  const p = new Page();

  letterhead(p, RETAILER.name, `${RETAILER.address} — Tel. (011) 4864-7700`, RETAILER.cuit);

  p.text('FACTURA  B', { font: 'F2', size: 14, center: true, leading: 15 });
  p.text('Documento no válido como crédito fiscal — Consumidor Final', { size: 8, center: true });
  p.gap(8);

  p.field('Comprobante N°:', PURCHASE.invoice);
  p.field('Fecha de emisión:', PURCHASE.date);
  p.field('Condición frente al IVA:', 'Responsable Inscripto');
  p.field('Ingresos Brutos:', '901-914628-3    Inicio de actividades: 07/2008');
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
  p.text(`  1     Notebook ${DEVICE.brand} ${DEVICE.model} — ${DEVICE.specs}`, { size: 9 });
  p.text(`        Color ${DEVICE.color} — teclado español latinoamericano`, { size: 9 });
  p.text(`        N° de serie: ${DEVICE.serial} — Garantía oficial 12 meses`, { size: 9 });
  p.gap(8);

  p.moneyRow('Subtotal (neto gravado)', `$ ${PURCHASE.net}`);
  p.moneyRow('IVA 21%', `$ ${PURCHASE.vat}`);
  p.gap(2);
  p.moneyRow('TOTAL', `$ ${PURCHASE.total}`, { size: 11, bold: true, leading: 16 });

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
function deviceLock(sc) {
  const p = new Page();
  const { lock } = sc;
  const issued = emitidaLaMananaSiguiente(sc.event, 770);

  letterhead(p, SERVICE.name, `${SERVICE.address} — Tel. (011) 4832-5500`, SERVICE.cuit);

  p.text('CONSTANCIA DE BLOQUEO REMOTO Y REGISTRO DE EQUIPO SUSTRAÍDO', { font: 'F2', size: 11.5, center: true, leading: 14 });
  p.text('Servicio Técnico Autorizado — Gestión de equipos denunciados', { size: 8.5, center: true });
  p.gap(6).rule();

  p.field('Constancia N°:', lock.number);
  p.field('Orden de gestión N°:', lock.ticket);
  p.field('Fecha de emisión:', d(issued));
  p.gap(6);

  p.section('DATOS DEL TITULAR');
  p.field('Apellido y nombre:', INSURED.formal);
  p.field('Documento:', `DNI ${INSURED.dni}`);
  p.field('Cuenta asociada:', INSURED.email);
  p.field('Teléfono de contacto:', INSURED.phone);
  p.gap(6);

  p.section('DATOS DEL EQUIPO');
  p.field('Marca y modelo:', `${DEVICE.brand} ${DEVICE.model}`);
  p.field('Configuración:', `${DEVICE.specs} — ${DEVICE.color}`);
  p.field('N° de serie:', DEVICE.serial);
  p.field('Dirección MAC (Wi-Fi):', DEVICE.mac);
  p.field('IMEI:', 'no aplica — el equipo no posee módem de telefonía móvil');
  p.gap(6);

  p.section('DATOS DE LA GESTIÓN');
  p.field('Motivo:', `${sc.claimCause} del equipo`);
  p.field('Bloqueo solicitado:', `${d(lock.requested)}, ${hms(lock.requested)} hs.`);
  p.field('Canal:', lock.canal);
  p.field('Bloqueo aplicado:', lock.aplicado
    ? `${d(lock.applied)}, ${hms(lock.applied)} hs.`
    : 'pendiente — el equipo no volvió a conectarse a la red');
  p.field('Actuación policial:', `N° ${sc.police.number}, ${sc.police.station}`);
  p.gap(8);

  p.box(30);
  p.text(`ESTADO ACTUAL DEL EQUIPO:  ${lock.estado}`, { font: 'F2', size: 10, x: MARGIN + 12, leading: 12 });
  p.text(`N° de serie ${DEVICE.serial} registrado como sustraído en la base del fabricante.`,
    { size: 8.5, x: MARGIN + 12 });
  p.gap(14);

  p.section('ALCANCE DEL BLOQUEO');
  (lock.aplicado
    ? [
      'A partir de la fecha y hora de aplicación, el equipo identificado con el número de serie',
      'consignado queda bloqueado contra la cuenta de su titular: no puede desbloquearse, borrarse,',
      `reinstalarse ni asociarse a otra cuenta sin las credenciales ${G.del} denunciante. El registro`,
      'del número de serie impide además su ingreso a la red de servicio técnico autorizado para',
      'reparación o venta de partes.',
    ]
    : [
      'La orden de bloqueo quedó registrada y se aplicará de manera automática en cuanto el equipo',
      'se conecte a cualquier red. Hasta entonces el equipo permanece operativo para quien lo',
      'tenga en su poder, aunque su número de serie ya figura registrado como sustraído: eso',
      'impide su ingreso a la red de servicio técnico autorizado y su asociación a una cuenta',
      'nueva del fabricante.',
    ]).forEach((l) => p.text(l, { size: 9 }));
  p.gap(8);
  [
    'Se deja constancia de que la solicitud fue registrada en la fecha y hora indicadas, y de que',
    `${G.el} titular acreditó la denuncia policial consignada al iniciar la gestión.`,
  ].forEach((l) => p.text(l, { size: 9 }));
  p.gap(10);

  p.text(`La presente se extiende a pedido ${G.del} titular para ser presentada ante su compañía`, { size: 8.5 });
  p.text('aseguradora.', { size: 8.5 });
  p.gap(22);
  p.text('...........................................', { size: 9 });
  p.text('        Mesa de Gestiones', { size: 8.5 });
  p.text(`        ${SERVICE.name}`, { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: `Constancia de bloqueo remoto ${lock.number}`,
    author: SERVICE.name,
    subject: `Bloqueo de equipo portatil por ${sc.claimCause.toLowerCase()} - documento simulado de prueba`,
    created: pdfDate(issued),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 4 · last_connection — último registro del servicio de localización
// ─────────────────────────────────────────────────────────────────────────────
function lastConnection(sc) {
  const p = new Page();
  const lc = sc.lastConnection;
  const issued = emitidaLaMananaSiguiente(sc.event, 770);

  letterhead(p, SERVICE.name, `${SERVICE.address} — Área Técnica / Servicio de localización`, SERVICE.cuit);

  p.text('INFORME DE ÚLTIMA CONEXIÓN REGISTRADA', { font: 'F2', size: 12.5, center: true, leading: 14 });
  p.gap(6).rule();

  p.field('Informe N°:', lc.number);
  p.field('Fecha de emisión:', d(issued));
  p.field('Solicitado por:', `${INSURED.display}, DNI ${INSURED.dni}`);
  p.gap(6);

  p.section('EQUIPO CONSULTADO');
  p.field('Marca y modelo:', `${DEVICE.brand} ${DEVICE.model}`);
  p.field('N° de serie:', DEVICE.serial);
  p.field('Dirección MAC (Wi-Fi):', DEVICE.mac);
  p.field('Cuenta asociada:', INSURED.email);
  p.gap(6);

  p.section('ÚLTIMOS REGISTROS DEL SERVICIO');
  p.gap(2);
  p.text('Fecha y hora                Evento                            Red / origen', { font: 'F2', size: 8.5, leading: 12 });
  // Segundos "sucios" a propósito: un log real no cae en :00 redondo.
  lc.registros.forEach(([min, seg, evento, origen]) => {
    const when = plus(sc.event, min, seg);
    p.text(`${d(when)}  ${hms(when)}        ${evento}    ${origen}`, { size: 8.5 });
  });
  p.gap(10);

  p.section('RESULTADO DE LA CONSULTA');
  p.field('Último registro:', `${d(sc.lock.applied)}, ${hms(sc.lock.applied)} hs.`);
  p.field('Red utilizada:', lc.network);
  p.field('Dirección IP pública:', lc.ip);
  p.field('Ubicación aproximada:', lc.area);
  p.field('Precisión estimada:', 'radio de 250 m (posicionamiento por red, sin GPS)');
  p.field('Registros posteriores:', 'ninguno');
  p.field('Estado del equipo:', `bloqueado (ver constancia ${sc.lock.number})`);
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
  p.text(`        ${SERVICE.name}`, { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: `Informe de ultima conexion registrada ${lc.number}`,
    author: SERVICE.name,
    subject: 'Ultima conexion del equipo portatil - documento simulado de prueba',
    created: pdfDate(issued),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 5 · repair_quote — presupuesto de reparación
// ─────────────────────────────────────────────────────────────────────────────
function repairQuote(sc) {
  const p = new Page();
  const r = sc.repair;

  letterhead(p, SERVICE.name, `${SERVICE.address} — Tel. (011) 4832-5500`, SERVICE.cuit);

  p.text('PRESUPUESTO DE REPARACIÓN', { font: 'F2', size: 12.5, center: true, leading: 14 });
  p.text('Servicio Técnico Autorizado — Consumidor Final', { size: 8.5, center: true });
  p.gap(6).rule();

  p.field('Presupuesto N°:', r.number);
  p.field('Orden de servicio:', r.order);
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
  p.field('Configuración:', `${DEVICE.specs} — ${DEVICE.color}`);
  p.field('N° de serie:', DEVICE.serial);
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
  p.gap(20);

  p.text('...........................................', { size: 9 });
  p.text('        Departamento Técnico', { size: 8.5 });
  p.text(`        ${SERVICE.name}`, { size: 8.5 });

  if (PROFILE.disclaimer) footer(p);
  return build(p, {
    title: `Presupuesto de reparacion ${r.number}`,
    author: SERVICE.name,
    subject: 'Presupuesto por danio accidental - documento simulado de prueba',
    created: pdfDate(r.issued),
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// Salida
// ─────────────────────────────────────────────────────────────────────────────
const BUILDERS = {
  police_report: [policeReport, 'denuncia_policial_tecnologia.pdf'],
  purchase_proof: [purchaseProof, 'factura_compra_tecnologia.pdf'],
  imei_deregistration: [deviceLock, 'bloqueo_equipo_tecnologia.pdf'],
  last_connection: [lastConnection, 'ultima_conexion_tecnologia.pdf'],
  repair_quote: [repairQuote, 'presupuesto_reparacion_tecnologia.pdf'],
};

const root = outDirFromArgv() || path.join(__dirname, PROFILE.folder, 'tec-portatil');
console.log(`Destino: ${root}\n`);

for (const sc of SCENARIOS) {
  const dir = path.join(root, sc.folder);
  fs.mkdirSync(dir, { recursive: true });
  console.log(`${sc.folder}/  (${sc.claimCause})`);

  for (const type of sc.documents) {
    const [builder, filename] = BUILDERS[type];
    const bytes = builder(sc);
    fs.writeFileSync(path.join(dir, filename), bytes);
    console.log(`  ${type.padEnd(20)} ${filename.padEnd(40)} ${bytes.length} bytes`);
  }

  const payload = {
    branch: BRANCH,
    product: PRODUCT,
    claimCause: sc.claimCause,
    insuredItem: `Apple MacBook Air 15" M3 512 GB`,
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
  const payloadName = `caso_${sc.folder}.json`;
  fs.writeFileSync(path.join(dir, payloadName), JSON.stringify(payload, null, 2) + '\n');
  console.log(`  ${'case (payload)'.padEnd(20)} ${payloadName.padEnd(40)} ${d(sc.event)} ${hm(sc.event)}`);
  console.log(`  ${'item_photo'.padEnd(20)} ${'../foto_notebook_para_fraude.jpg  (adjuntar desde la raíz)'}`);
  console.log();
}

const expira = plus(ROBO.event, 96 * 60);
console.log(`Los sets vencen el ${d(expira)} ${hm(expira)} — después D11 (plazo de denuncia, 96 hs`);
console.log('de la cobertura del ramo) bloquea el Fast Track. Volvé a correr este script para renovarlos.');
