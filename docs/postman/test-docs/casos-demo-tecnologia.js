// The final presentation's Tecnología Portátil cases in BBVA. Case 1 is a phone:
// casos-demo-celulares.js.
//
//   demo/tomas/tec-portatil/rotura/    Tomás Ibarra, case 2, filed live (perfiles.js, `demoTomas`)
//   demo/caso2/tec-portatil/rotura/    Valentín Aguirre, the same story (earlier version of the case)
//   demo/semilla/tec-portatil/rotura/  Roman Castillo, an earlier claim with the same photo
//
// The presentation now shows Google Vision finding the photo on the web, which only runs when no
// other BBVA case has it: the caso2 and semilla sets must not be loaded alongside Tomás's.
//
// Market-like values. The shops and the people are fictitious.

const { cuit, plus } = require('./lib-pdf');

// ─── The second case: accidental damage with a reused photo ────────────────
//
// Valentín (BBVA, POL-TEC-2026-782) says his Lenovo fell off the living room table and the screen
// broke. He files the invoice and the repair quote; 445.000 over a 600.000 damage sum (0.74) is past
// Fast Track's 0.6, so the claim reaches the analyst, who asks for the photo of the device. The photo
// he sends (foto_notebook_rota.jpg) carries the two signals:
//   · it is published on the web (Wikimedia Commons): Google Vision's web detection finds it,
//     without the language model. If another BBVA case already has it, the pgvector comparison
//     finds that one instead and Vision isn't asked;
//   · it shows a 2009 Acer Extensa with Windows 7 stickers, not the 2025 Lenovo of the invoice and
//     the quote: no rule compares a photo with an invoice, so only the model can see it.
// The rest of the documents are consistent.

const PHOTO = 'foto_notebook_rota.jpg';

// Two days back: the repair shop's intake and quote fall on the next day in business hours, whatever
// the time of the run. The 96 h report deadline still leaves two days to file.
const anteayerA = (ayerA, h, m) => plus(ayerA(h, m), -24 * 60);

function valentin({ ayerA, INSURED }) {
  const event = anteayerA(ayerA, 21, 30);
  return {
    device: {
      brand: 'LENOVO',
      model: 'IdeaPad Slim 3 15IRU9',
      specs: 'Core i5, 16 GB, 512 GB SSD',
      color: 'Gris abismo',
    },
    insuredItem: 'Lenovo IdeaPad Slim 3 15 512 GB',
    purchase: {
      date: '10/03/2026',
      created: "20260310164000-03'00'",
      invoice: '0004-00015862',
      total: '1.099.999,00',
      vat: '190.908,92',
      cae: '76104883920154',
      caeDue: '20/03/2026',
      payment: 'Tarjeta de Crédito',
    },
    retailer: {
      name: 'Bitstore',
      legalName: 'BITSTORE INFORMATICA S.R.L.',
      address: 'Av. Santa Fe 3250 - Ciudad de Buenos Aires',
      cuit: cuit('30', '71638274'),
      iibb: '901-638274-2',
      since: '01/02/2018',
    },
    buyer: {
      dni: INSURED.dni,
      name: 'AGUIRRE VALENTIN',
      address: 'Av. Scalabrini Ortiz 1920 Piso:3 Dpto:C - Ciudad de Buenos Aires',
    },
    item: {
      code: 'LEN-S3-15I5-512',
      description: 'Notebook Lenovo IdeaPad Slim 3 i5 16GB 512GB Gris',
      details: ['Modelo 15IRU9 - N° de serie: PF5M3K9Q', 'Garantía oficial 12 meses'],
    },
    shop: {
      name: 'Reparo Notebooks',
      legalName: 'REPARO SERVICIOS INFORMATICOS S.R.L.',
      address: 'Av. Córdoba 4520, C.A.B.A.',
      phone: '(011) 5555-0244',
      cuit: cuit('30', '71844091'),
    },
    scenarios: [{
      folder: 'rotura',
      claimCause: 'Rotura accidental',
      documents: ['purchase_proof', 'repair_quote'],
      photo: PHOTO,
      event,
      claimedAmount: 445000,
      eventLocation: 'Av. Scalabrini Ortiz 1920, piso 3° "C" - domicilio particular',
      locality: 'Palermo',
      province: 'Ciudad Autónoma de Buenos Aires',
      description:
        'Estaba trabajando en el living con la notebook sobre la mesa ratona. Me levanté, enganché el '
        + 'cable del cargador y la notebook se cayó al piso de golpe. La pantalla quedó rota. La llevé '
        + 'al service y me pasaron el presupuesto.',
      repair: {
        order: '0012874',
        received: plus(event, 13 * 60 + 15),
        issued: plus(event, 15 * 60 + 40),
        accessories: 'Cargador original',
        condition: 'Pantalla rota. Enciende.',
        reported:
          'El cliente manifiesta que el equipo se cayó de la mesa del living la noche anterior y que la '
          + 'pantalla quedó rota. Enciende y se escucha el arranque.',
        diagnosis: [
          'Panel LED de 15,6" con rotura del vidrio y del cristal líquido por impacto. Con monitor externo el '
          + 'equipo arranca y funciona normalmente.',
          'Bisagra derecha con el anclaje de la tapa fisurado. Teclado, touchpad, placa y batería sin daños.',
        ],
        items: [
          ['LCD-156-FHD30', 'Panel LED 15,6" FHD IPS 30 pines - original', 1, 352000],
          ['BIS-LS3-DER', 'Bisagra derecha IdeaPad Slim 3', 1, 38000],
          ['MO-PANT', 'Mano de obra: desarme, reemplazo de panel y bisagra, prueba', 1, 55000],
        ],
        technician: 'Martín D. Olivera',
      },
    }],
  };
}

// Valentín's story filed by Tomás (BBVA, POL-TEC-2026-640): his account has a mailbox the demo can
// open. Only what places it changes: the buyer, his home in La Plata and a repair shop there. His
// damage sum is 600.000 like Valentín's (db/migrations/2026-11-21-tomas-caso2.sql), so 445.000 still
// misses Fast Track.
function tomas(ctx) {
  const base = valentin(ctx);
  return {
    ...base,
    buyer: {
      dni: ctx.INSURED.dni,
      name: 'IBARRA TOMAS',
      address: 'Calle 49 N° 812 Piso:3 Dpto:B - La Plata, Buenos Aires',
    },
    // The serial matches perfiles.js, which the repair quote reads.
    item: { ...base.item, details: ['Modelo 15IRU9 - N° de serie: PF5N7R2X', 'Garantía oficial 12 meses'] },
    shop: {
      name: 'Platense Notebooks',
      legalName: 'PLATENSE SERVICIOS INFORMATICOS S.R.L.',
      address: 'Calle 8 N° 1020, La Plata',
      phone: '(0221) 555-0318',
      cuit: cuit('30', '71902336'),
    },
    scenarios: base.scenarios.map((sc) => ({
      ...sc,
      eventLocation: 'Calle 49 N° 812, piso 3° "B" - domicilio particular',
      locality: 'La Plata',
      province: 'Buenos Aires',
    })),
  };
}

// Roman's seed case: an earlier accidental damage claim in BBVA with the same photo. It is loaded
// the day before and taken through the photo request, so its embedding is stored when Valentín's
// claim is classified. Not loaded while case 2 shows Google Vision: with it, Tomás's photo is an
// internal duplicate and Vision isn't asked.
function semilla({ ayerA, INSURED }) {
  const event = anteayerA(ayerA, 11, 10);
  return {
    device: {
      brand: 'ACER',
      model: 'Aspire 5 A515-58M',
      specs: 'Core i5, 8 GB, 512 GB SSD',
      color: 'Gris acero',
    },
    insuredItem: 'Acer Aspire 5 512 GB',
    purchase: {
      date: '15/01/2026',
      created: "20260115113000-03'00'",
      invoice: '0009-00044127',
      total: '849.999,00',
      vat: '147.520,49',
      cae: '76031259844370',
      caeDue: '25/01/2026',
      payment: 'Tarjeta de Débito',
    },
    retailer: {
      name: 'Megatech Once',
      legalName: 'MEGATECH ONCE S.A.',
      address: 'Av. Pueyrredón 120 - Ciudad de Buenos Aires',
      cuit: cuit('30', '71455216'),
      iibb: '901-455216-8',
      since: '01/06/2013',
    },
    buyer: {
      dni: INSURED.dni,
      name: 'CASTILLO ROMAN',
      address: 'Av. Warnes 1470 Piso:2 Dpto:A - Ciudad de Buenos Aires',
    },
    item: {
      code: 'ACR-A5-I5-512',
      description: 'Notebook Acer Aspire 5 A515-58M i5 8GB 512GB Gris',
      details: ['N° de serie: NXKHGAL00R4421', 'Garantía oficial 12 meses'],
    },
    shop: {
      name: 'TecnoWarnes',
      legalName: 'TECNOWARNES S.R.L.',
      address: 'Av. Warnes 1820, C.A.B.A.',
      phone: '(011) 5555-0318',
      cuit: cuit('30', '71720453'),
    },
    scenarios: [{
      folder: 'rotura',
      claimCause: 'Rotura accidental',
      documents: ['purchase_proof', 'repair_quote'],
      photo: PHOTO,
      event,
      claimedAmount: 368000,
      eventLocation: 'Av. Warnes 1500, parada de colectivo',
      locality: 'La Paternal',
      province: 'Ciudad Autónoma de Buenos Aires',
      description:
        'Bajando del colectivo se me abrió la mochila y la notebook cayó a la vereda. La pantalla quedó '
        + 'toda rajada. El service me presupuestó el cambio del panel.',
      repair: {
        order: 'TW-3391',
        received: plus(event, 5 * 60),
        issued: plus(event, 6 * 60 + 30),
        accessories: 'Sin accesorios',
        condition: 'Pantalla rajada. Enciende.',
        reported: 'El cliente manifiesta que la notebook cayó a la vereda al bajar del colectivo y la pantalla se rajó.',
        diagnosis: ['Panel LED de 15,6" rajado en toda su superficie por impacto. El equipo funciona con monitor externo.'],
        items: [
          ['PAN-156-FHD', 'Panel LED 15,6" FHD 30 pines', 1, 318000],
          ['MO-PANT', 'Mano de obra: reemplazo de panel y prueba', 1, 50000],
        ],
        technician: 'Julio C. Benítez',
      },
    }],
  };
}

/** The demo case of the profile, built against the run's dates. */
function buildDemo(insurer, ctx) {
  const build = { tomas, valentin, semilla }[insurer];
  if (!build) throw new Error(`No hay caso de demo para la aseguradora "${insurer}"`);
  return build(ctx);
}

module.exports = { buildDemo };
