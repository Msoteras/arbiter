// Espejo de PolicyResponse de cases-service (GET /api/v1/policies).
// Multi-aseguradora: cada póliza trae su aseguradora (insurerId/insurerName).

/**
 * Una cobertura contratada en la póliza. Son VARIAS: una póliza de celulares cubre robo y hurto,
 * cada una con su propia suma asegurada y franquicia. Cuál aplica lo decide el hecho generador
 * denunciado, y eso lo resuelve el backend.
 */
export interface PolicyCoverage {
  code: string;
  description: string;
  insuredAmount: number;
  /** Franquicia en valor absoluto. */
  deductible: number;
  /** La misma franquicia en puntos porcentuales (10 = 10%), como la da la compañía. */
  deductiblePct: number | null;
}

export interface Policy {
  policyNumber: string;
  insurerId: string;
  insurerName: string;
  insuredName: string;
  insuredId: string;
  contactEmail: string | null;
  contactPhone: string | null;
  branch: string;
  insuredItem: string | null;
  product: string;
  /** ISO yyyy-MM-dd. */
  effectiveFrom: string;
  effectiveTo: string;
  upToDate: boolean;
  /**
   * Suma asegurada y franquicia de la PRIMERA cobertura, solo para el resumen de la tarjeta de
   * póliza. No hay una suma asegurada de la póliza: cualquier cosa que decida algo tiene que
   * mirar `coverages` y quedarse con la que corresponde al hecho denunciado.
   */
  insuredAmount: number;
  deductible: number;
  coverages: PolicyCoverage[];
}
