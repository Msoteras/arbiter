// Node bridge to Escanear.java. Only the scanned/photographed/tampered fixtures need it, so the
// regular generators keep running without Java; the ones that do need it fail here with a message
// that says what's missing instead of a stack trace.
//
// The classpath is PDFBox 3.0.3 from the local Maven repository: the same version
// classification-service rasterizes with, already there after any `mvn install`.

const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const M2 = path.join(os.homedir(), '.m2', 'repository');
const JARS = [
  'org/apache/pdfbox/pdfbox/3.0.3/pdfbox-3.0.3.jar',
  'org/apache/pdfbox/fontbox/3.0.3/fontbox-3.0.3.jar',
  'org/apache/pdfbox/pdfbox-io/3.0.3/pdfbox-io-3.0.3.jar',
];
const SOURCE = path.join(__dirname, 'Escanear.java');

function classpath() {
  const jars = JARS.map((j) => path.join(M2, j));
  // PDFBox logs through commons-logging; any 1.x the repository has will do.
  const logging = path.join(M2, 'commons-logging', 'commons-logging');
  const version = fs.existsSync(logging) ? fs.readdirSync(logging).filter((v) => /^1\./.test(v)).sort().pop() : null;
  if (version) jars.push(path.join(logging, version, `commons-logging-${version}.jar`));
  const missing = jars.filter((j) => !fs.existsSync(j));
  if (missing.length) {
    throw new Error(`Faltan jars de PDFBox en ${M2} (corré \`mvn install\` una vez): ${missing.join(', ')}`);
  }
  return jars.join(path.delimiter);
}

/**
 * A stable seed per output file, so regenerating doesn't churn the binaries in git. Folder
 * included: the same document name repeats across cases and shouldn't get the same tilt.
 */
function seedOf(file) {
  const key = path.join(path.basename(path.dirname(file)), path.basename(file));
  let h = 0;
  for (const ch of key) h = (h * 31 + ch.charCodeAt(0)) | 0;
  return Math.abs(h);
}

function run(args) {
  execFileSync('java', ['-cp', classpath(), SOURCE, ...args], { stdio: ['ignore', 'ignore', 'pipe'] });
}

/**
 * @param patches [{ find, replace }] fields pasted over the scan, see Escanear.java
 */
function scan(inPdf, outPdf, patches = []) {
  run(['scan', inPdf, outPdf, String(seedOf(outPdf)), ...patches.map((p) => `${p.find}|${p.replace}`)]);
}

function photo(inPdf, outJpg) {
  run(['photo', inPdf, outJpg, String(seedOf(outJpg))]);
}

function raw(inPdf, outRgba) {
  run(['raw', inPdf, outRgba]);
}

module.exports = { scan, photo, raw };
