// Los dos juegos de fixtures son los mismos casos con distinto firmante. Acá vive lo único que
// cambia entre las variantes, para que los generadores conserven una sola copia del layout de cada
// documento:
//
//   conMarcaDePrueba/  Martina Soteras, con la leyenda de "documento simulado" al pie de cada página.
//   sinMarca/          Roman Castillo, sin leyenda: la hoja queda como llegaría la de un asegurado
//                      real. Es para probar el pipeline sin que el modelo de visión lea un cartel
//                      que le anticipa que el documento es de prueba.
//
// Los PDFs de las dos variantes se siguen identificando como fixtures en los metadatos
// (/Subject, /Keywords, /Producer): no se ven en la página ni entran al OCR, pero acompañan al
// archivo aunque salga de esta carpeta.

const path = require('path');

// Concordancia de género del relato. "denunciante", "titular" y "solicitante" son epicenos: lo único
// que cambia es el artículo, así que con estas piezas alcanza para que un acta no le atribuya el
// sexo equivocado a quien la firma.
const FEMENINO = { el: 'la', El: 'La', del: 'de la', al: 'a la', por: 'por la', DEL: 'DE LA', a: 'a' };
const MASCULINO = { el: 'el', El: 'El', del: 'del', al: 'al', por: 'por el', DEL: 'DEL', a: 'o' };

// The phone every Celulares document describes, unless the profile's policy insures another one.
// It has to match the policy's `bien_asegurado`: DocumentInconsistencyEvaluator checks the brand
// and model on each document against it.
const SAMSUNG_A56 = { brand: 'SAMSUNG', model: 'Galaxy A56 5G', color: 'Gris (Awesome Graphite)', storage: '256 GB' };

const PROFILES = {
  conMarca: {
    folder: 'conMarcaDePrueba',
    disclaimer: true,
    g: FEMENINO,
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
    // Sus pólizas reales (db/seed-demo.sql PART 1-5). El número y el IMEI del equipo
    // viajan por acá para que los dos generadores (celulares/tecnología) los tomen del
    // mismo lugar que el resto del perfil, en vez de una constante hardcodeada compartida
    // entre variantes — eso fue lo que hizo que sinMarca terminara apuntando a esta misma
    // póliza con el DNI de Roman (ver policies.sinMarca, más abajo: la corrigió D-Roman).
    policies: {
      celulares: { number: 'POL-CEL-2026-042', imei: '351000000000042', serial: 'RZ8W60K3XPL', device: SAMSUNG_A56 },
      tecnologia: { number: 'POL-TEC-2026-311', serial: 'H7QWK3F9LM' },
    },
  },
  sinMarca: {
    folder: 'sinMarca',
    disclaimer: false,
    g: MASCULINO,
    insured: {
      formal: 'CASTILLO, Roman',
      display: 'Roman Castillo',
      dni: '33.845.219',
      cuil: '20-33845219-6',
      birth: '02/09/1988',
      address: 'Av. Warnes 1470, piso 2° "A", C.A.B.A.',
      phone: '11-5555-0007',
      email: 'roman.castillo@example.com',
    },
    // Su propia póliza en cada tenant (db/migrations/2026-08-21-roman-castillo.sql),
    // mismos montos que la de Martina para que el caso se comporte igual — pero SU
    // número, no el de ella. Antes las dos variantes reusaban policies.celulares/
    // tecnologia de Martina con el DNI de Roman puesto encima: un DNI real contra la
    // póliza de otra persona, que PolicyEligibilityValidator (D2) rechaza siempre, sin
    // importar qué tan bien armados estén los documentos.
    policies: {
      celulares: { number: 'POL-CEL-2026-350', imei: '359000000000350', serial: 'RZ8W60K9YQM', device: SAMSUNG_A56 },
      tecnologia: { number: 'POL-TEC-2026-350', serial: 'H7QWK3F2RB' },
    },
  },
  // Signs only the mutations (mutaciones-celulares.js), never the regular sets. A separate insured
  // because every case filed in Arbiter counts as a prior claim of whoever filed it: a single
  // earlier run already fails Fast Track's max prior claims (0), and Martina carries 15 in BBVA.
  // A mutation can't tell its own signal apart from that. This one is wiped before each
  // batch with scripts/reset-asegurados-de-prueba.sql, which touches no one but the fixture insureds.
  mutaciones: {
    folder: 'mutaciones',
    disclaimer: false,
    g: MASCULINO,
    insured: {
      formal: 'AGUIRRE, Valentín',
      display: 'Valentín Aguirre',
      dni: '38.614.270',
      cuil: '20-38614270-0',
      birth: '27/05/1994',
      address: 'Av. Scalabrini Ortiz 1920, piso 3° "C", C.A.B.A.',
      phone: '11-5555-0077',
      email: 'valentin.aguirre@example.com',
    },
    // Who had the phone on them in the bien-de-familiar mutation.
    spouse: { name: 'Julieta Sosa', dni: '39.208.114', g: FEMENINO },
    // db/migrations/2026-09-22-asegurado-mutaciones.sql
    policies: {
      celulares: { number: 'POL-CEL-2026-777', imei: '357000000000777', serial: 'RZ8W60K7TQA', device: SAMSUNG_A56 },
    },
  },
  // A clean insured for the regular sets and for the history sequence (historial-celulares.js),
  // which files three claims in a row on purpose to watch prior claims kick in. Wiped before each
  // run with scripts/reset-asegurados-de-prueba.sql. Her account was created by the bulk
  // provisioning (db/migrations/2026-08-25-asegurado-sin-cuenta.sql).
  camila: {
    folder: 'camila',
    disclaimer: false,
    g: FEMENINO,
    insured: {
      formal: 'FERREYRA, Camila',
      display: 'Camila Ferreyra',
      dni: '38.412.905',
      cuil: '27-38412905-4',
      birth: '19/11/1994',
      address: 'Av. Díaz Vélez 4410, piso 6° "A", C.A.B.A.',
      phone: '11-5555-0042',
      email: 'camila.ferreyra@example.com',
    },
    // Her policy (POL-CEL-2026-401) covers Robo and Hurto only: no Daño accidental, so no caída or
    // rotura sets. The policy carries no IMEI, so checkImei doesn't take part; the documents still
    // state one, as a real phone's would.
    scenarios: ['robo', 'hurto'],
    policies: {
      celulares: {
        number: 'POL-CEL-2026-401',
        imei: '355200000000401',
        serial: 'GA04832-8B1',
        device: { brand: 'GOOGLE', model: 'Pixel 8', color: 'Negro (Obsidian)', storage: '128 GB' },
      },
    },
  },
};

/** `--sin-marca`, `--camila` o `--mutaciones` en la línea de comandos; sin flag, el juego con leyenda. */
function variantFromArgv(argv = process.argv) {
  if (argv.includes('--mutaciones')) return 'mutaciones';
  if (argv.includes('--camila')) return 'camila';
  return argv.includes('--sin-marca') ? 'sinMarca' : 'conMarca';
}

/** El destino que no es un flag, si lo pasaron: reemplaza a la raíz de la variante. */
function outDirFromArgv(argv = process.argv) {
  const positional = argv.slice(2).filter((a) => !a.startsWith('--'));
  return positional.length ? path.resolve(positional[0]) : null;
}

module.exports = { PROFILES, variantFromArgv, outDirFromArgv };
