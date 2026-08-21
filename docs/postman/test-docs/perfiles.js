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
  },
};

/** `--sin-marca` en la línea de comandos; sin flag, el juego con leyenda. */
function variantFromArgv(argv = process.argv) {
  return argv.includes('--sin-marca') ? 'sinMarca' : 'conMarca';
}

/** El destino que no es un flag, si lo pasaron: reemplaza a la raíz de la variante. */
function outDirFromArgv(argv = process.argv) {
  const positional = argv.slice(2).filter((a) => !a.startsWith('--'));
  return positional.length ? path.resolve(positional[0]) : null;
}

module.exports = { PROFILES, variantFromArgv, outDirFromArgv };
