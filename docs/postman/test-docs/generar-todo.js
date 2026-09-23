// Regenerates every fixture set in one go: the dates are relative to the run and the sets expire
// (README §3), so refreshing them shouldn't mean remembering seven commands.
//
// Uso: node docs/postman/test-docs/generar-todo.js

const { execFileSync } = require('child_process');
const path = require('path');

const RUNS = [
  ['generar-fixtures.js'],
  ['generar-fixtures.js', '--sin-marca'],
  ['generar-fixtures.js', '--camila'],
  ['generar-fixtures.js', '--mutaciones'],
  ['generar-fixtures-tecnologia.js'],
  ['generar-fixtures-tecnologia.js', '--sin-marca'],
];

for (const [script, ...args] of RUNS) {
  const label = [script, ...args].join(' ');
  const out = execFileSync(process.execPath, [path.join(__dirname, script), ...args], { encoding: 'utf8' });
  // Each generator prints its expiry; the earliest one is when this set stops being valid. Sorted
  // as dd/mm within the same month, which is all a set's 72-96 h window can span in practice.
  const expiries = [...out.matchAll(/vencen? el (\d{2}\/\d{2}\/\d{4} \d{2}:\d{2})/g)].map((m) => m[1]);
  console.log(`${label.padEnd(45)} vence ${expiries.sort()[0]}`);
}
