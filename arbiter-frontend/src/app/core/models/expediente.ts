import { Clasificacion } from './clasificacion';
import { ImageForensicReport } from './forensic';

// Espejo de StatusTransitionResponse del cases-service.
// `fromStatus` es null en la fila de creación del expediente.
export interface StatusTransition {
  fromStatus: string | null;
  toStatus: string;
  actor: 'SYSTEM' | 'INSURED' | 'ANALYST';
  reason: string;
  changedAt: string;
}

/**
 * El aporte de un factor al score de fraude — espejo de RiskBreakdownItem del back. Lo muestra la
 * vista del analista para que el score no sea una caja negra: qué factor pesó y por qué.
 */
export interface RiskBreakdownItem {
  factorId: string;
  /** Contribución normalizada del factor en [0,1]. */
  rawScore: number;
  /** Peso de la aseguradora para este factor. */
  weight: number;
  /** rawScore * weight — el empuje absoluto de este factor sobre el total. */
  weightedContribution: number;
  rationale: string;
}

/**
 * Lo que el modelo leyó de un adjunto — espejo de DocumentAnalysisSummary del back (H0031).
 *
 * Un campo en `null` significa **"el documento no lo dice"**, nunca "no coincide": una foto del
 * bien no trae importe y una denuncia policial no trae IMEI. Se muestra como "No aplica", jamás
 * como discrepancia — leer un campo ausente como inconsistencia sería acusar al asegurado por
 * algo que nadie declaró.
 */
export interface DocumentAnalysis {
  /** El slot de la agenda documental que cumple el adjunto (`police_report`, …). */
  documentType: string;
  /** Lo que dice el documento, en texto plano. */
  transcription: string;
  documentDate: string | null;
  amount: number | null;
  itemDescription: string | null;
  imei: string | null;
  /** TITULAR | FAMILIAR | TERCERO | DESCONOCIDO — quién sufrió el hecho según el documento. */
  affectedParty: string;
  /**
   * Señales de manipulación que el modelo de visión notó en la imagen. **Vacío es lo normal**, y
   * que esté vacío no prueba que el documento sea auténtico.
   */
  visualFindings: string[];
}

// Espejo de CaseResponse del cases-service (GET /api/v1/cases/{id})
export interface ExpedienteResponse {
  id: number;
  /**
   * De qué aseguradora es. Sólo vienen en "mis siniestros" del asegurado, la única vista que
   * mezcla compañías. `insurerSlug` es lo que se usa para volver a pedir el expediente (`id` se
   * repite entre aseguradoras); `insurerName` es para mostrarlo, porque dos siniestros con el
   * mismo número no se distinguen si no se dice de quién es cada uno.
   */
  insurerSlug?: string | null;
  insurerName?: string | null;
  status: string;
  branch: string;
  product: string;
  claimCause: string;
  insuredItem: string;
  insuredId: string;
  /** Nombre real del asegurado, resuelto por classification-service al clasificar. Null hasta entonces. */
  insuredName: string | null;
  /**
   * Persona políticamente expuesta, según lo declaró el asegurado al denunciar (UIF/PLA). Es
   * debida diligencia, no una señal de fraude: se muestra entre los datos del asegurado para que
   * el analista lo tenga a la vista, y no participa del scoring ni de la clasificación (D16).
   */
  pep: boolean;
  policyNumber: string;
  description: string;
  eventDate: string;
  eventLocation: string;
  claimedAmount: number | null;
  riskBand: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL' | null;
  /** Score de fraude normalizado en [0,1]. Null cuando no se scoreó ("sin scorear"). */
  riskScore: number | null;
  /** Desglose del score por factor (analista-only). Null/[] cuando no se scoreó. */
  riskBreakdown: RiskBreakdownItem[] | null;
  /**
   * Análisis forense de imágenes (H0009), analista-only. Null cuando no corrió
   * (Fast Track o expediente sin adjuntos de imagen).
   */
  forensicReport: ImageForensicReport | null;
  /**
   * Analista dueño del expediente, por su id de analista dentro de la aseguradora (el mismo que
   * devuelve `GET /auth/users/analysts`). Null = sin asignar. No es el id de usuario de la
   * sesión: son tablas distintas.
   */
  assignedAnalystId: number | null;
  /** Nombre del analista asignado, resuelto por el backend. Null = sin asignar. */
  assignedAnalystName: string | null;
  analysisClassification: Clasificacion | string;
  analysisConfidence: number;
  /**
   * Los motivos detrás de `analysisClassification`, uno por elemento — espejo de `llm_reason`
   * (una fila por motivo), no un string armado con join. Vacío en Fast Track (los motivos serían
   * de la corrida anterior, no del gate) o cuando todavía no hay clasificación.
   */
  analysisReasons: string[];
  createdAt: string;
  updatedAt: string;
  /** Solo viene en GET /{id}; en listados es null. */
  statusHistory: StatusTransition[] | null;
  /**
   * Lo que el modelo leyó de cada adjunto. Como `statusHistory`, solo viene en GET /{id} — en un
   * listado sería un join por fila. Vacío cuando el expediente no se clasificó, se resolvió por
   * Fast Track sin leer nada, o se clasificó antes de que esto existiera.
   */
  documentAnalyses: DocumentAnalysis[];
}
