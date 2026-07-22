// Espejo de PolicyResponse de cases-service (GET /api/v1/policies).
// Multi-aseguradora: cada póliza trae su aseguradora (insurerId/insurerName).

export interface PolicyCoverage {
  code: string;
  description: string;
  insuredAmount: number;
  deductible: number;
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
  insuredAmount: number;
  deductible: number;
  coverages: PolicyCoverage[];
}
