import { Injectable, signal } from '@angular/core';
import { Observable, of } from 'rxjs';

import { RamoRules } from '../../core/models/business-rules';

/**
 * Configuración de reglas del referente (H "Configuración de reglas"), Ramo-céntrica.
 *
 * MOCK EN MEMORIA: rules-service todavía no existe. Los datos semilla salen de los manuales y
 * condiciones generales reales (Celular Protegido, Tecnología Portátil — BBVA Seguros). Cuando
 * exista el backend, esto pasa a HttpClient sin tocar los componentes:
 *   GET /api/v1/rules/ramos            → list()
 *   PUT /api/v1/rules/ramos/{ramoId}   → save()
 * Por eso los métodos ya devuelven Observable.
 */
@Injectable({ providedIn: 'root' })
export class RulesConfigService {
  private readonly store = signal<RamoRules[]>(structuredClone(SEED_RAMOS));

  list(): Observable<RamoRules[]> {
    return of(structuredClone(this.store()));
  }

  save(updated: RamoRules): Observable<RamoRules> {
    this.store.update((list) => list.map((r) => (r.id === updated.id ? structuredClone(updated) : r)));
    return of(structuredClone(updated));
  }

  /** Alta de un ramo nuevo (POST /api/v1/rules/ramos en el back futuro). */
  create(ramo: RamoRules): Observable<RamoRules> {
    this.store.update((list) => [...list, structuredClone(ramo)]);
    return of(structuredClone(ramo));
  }

  /** Baja de un ramo (DELETE /api/v1/rules/ramos/{ramoId} en el back futuro). */
  remove(ramoId: string): Observable<void> {
    this.store.update((list) => list.filter((r) => r.id !== ramoId));
    return of(undefined);
  }
}

// Config de scoring por defecto (H0012): 3 factores con lógica real re-pesados a sumar 1, y las
// bandas documentadas (Bajo / Medio / Alto / Crítico sobre 0..1).
const DEFAULT_SCORING = {
  enabled: true,
  factors: [
    { factorId: 'amount_ratio', weight: 0.45 },
    { factorId: 'claim_frequency', weight: 0.35 },
    { factorId: 'policy_standing', weight: 0.2 },
  ],
  bands: [
    { band: 'LOW' as const, minScoreInclusive: 0.0 },
    { band: 'MEDIUM' as const, minScoreInclusive: 0.3 },
    { band: 'HIGH' as const, minScoreInclusive: 0.6 },
    { band: 'CRITICAL' as const, minScoreInclusive: 0.8 },
  ],
};

const SEED_RAMOS: RamoRules[] = [
  {
    id: '1',
    name: 'Celular Protegido',
    coverages: [
      {
        id: 'robo',
        name: 'Robo',
        clause: '340',
        insuredAmount: null,
        deductibleRatio: 0.1,
        reportingWindowDays: 3,
        maxAnnualClaims: null,
        exclusions: ['Robo con participación del asegurado o grupo conviviente'],
      },
      {
        id: 'tentativa',
        name: 'Daño por tentativa de robo',
        clause: '340',
        insuredAmount: null,
        deductibleRatio: 0.1,
        reportingWindowDays: 3,
        maxAnnualClaims: null,
        exclusions: [],
      },
    ],
    commonExclusions: [
      'Hurto (apoderamiento ilegítimo sin fuerza ni violencia)',
      'Extravío del equipo',
      'Daño accidental (caída, rotura) no relacionado con el robo',
      'Equipo fuera del campo visual del asegurado',
      'Equipo en poder de otra persona',
    ],
    requiredDocuments: ['police_report', 'purchase_proof', 'imei_deregistration', 'last_connection'],
    businessRules: [
      'El asegurado debe notificar el siniestro dentro de los 3 días de ocurrido',
      'Contratable para familiares directos (hijos, padres, concubinos)',
      'La póliza debe estar al día al momento del siniestro',
    ],
    fastTrack: {
      enabled: true,
      minPolicyAgeMonths: 6,
      maxPriorClaims: 0,
      priorClaimsWindowMonths: 24,
      maxClaimedAmountRatio: 0.5,
      requiresUpToDatePolicy: true,
      requiredDocumentTypes: ['police_report'],
      criteria: [
        'Póliza con al menos 6 meses de vigencia',
        'Sin siniestros previos en los últimos 2 años',
        'Monto del reclamo dentro del límite (Anexo II)',
      ],
    },
    scoring: structuredClone(DEFAULT_SCORING),
  },
  {
    id: '3',
    name: 'Tecnología Portátil',
    coverages: [
      {
        id: 'robo',
        name: 'Robo',
        clause: '340',
        insuredAmount: null,
        deductibleRatio: 0.1,
        reportingWindowDays: 3,
        maxAnnualClaims: 2,
        exclusions: ['Si el siniestro ocurrió en el domicilio del asegurado'],
      },
      {
        id: 'tentativa',
        name: 'Daño por tentativa de robo',
        clause: '340',
        insuredAmount: null,
        deductibleRatio: 0.1,
        reportingWindowDays: 3,
        maxAnnualClaims: 2,
        exclusions: [],
      },
    ],
    commonExclusions: [
      'Hurto (apoderamiento ilegítimo sin fuerza ni violencia)',
      'Daño accidental (caída, rotura) no relacionado con el robo',
      'Equipos del grupo familiar (cobertura exclusiva del asegurado)',
      'Accesorios (manos libres, baterías, cargadores, tarjetas complementarias)',
    ],
    requiredDocuments: ['police_report', 'purchase_proof', 'imei_deregistration', 'last_connection'],
    businessRules: [
      '2 eventos indemnizables por año y póliza (el segundo se cubre al 50%)',
      'Cubre notebooks, netbooks, tablets, smartwatch, cámaras, auriculares y parlantes',
      'La póliza debe estar al día al momento del siniestro',
    ],
    fastTrack: {
      enabled: true,
      minPolicyAgeMonths: 6,
      maxPriorClaims: 0,
      priorClaimsWindowMonths: 24,
      maxClaimedAmountRatio: 0.5,
      requiresUpToDatePolicy: true,
      requiredDocumentTypes: ['police_report'],
      criteria: [
        'Póliza con al menos 6 meses de vigencia',
        'Sin siniestros previos en los últimos 2 años',
        'Monto del reclamo dentro del límite (Anexo II)',
      ],
    },
    scoring: structuredClone(DEFAULT_SCORING),
  },
];
