import { StatusMoves } from '../../../core/models/case-status';

/**
 * A copy of `CaseStatusService.VALID_TRANSITIONS` for the styleguide, which never calls the
 * backend, and for the layout spec. The app always reads the real one from cases-service.
 */
export const SAMPLE_STATUS_MOVES: StatusMoves[] = [
  {
    from: 'PENDING_CLASSIFICATION',
    to: ['PENDING_ANALYST_REVIEW', 'CLASSIFICATION_FAILED', 'AWAITING_DOCUMENTATION'],
  },
  {
    from: 'PENDING_ANALYST_REVIEW',
    to: [
      'PENDING_CLASSIFICATION',
      'AWAITING_DOCUMENTATION',
      'PENDING_EXPERT_REPORT',
      'PENDING_REPAIR',
      'APPROVED',
      'REJECTED',
      'PRESCRIBED',
      'WITHDRAWN',
      'VOIDED',
    ],
  },
  { from: 'CLASSIFICATION_FAILED', to: ['PENDING_CLASSIFICATION', 'WITHDRAWN', 'VOIDED'] },
  {
    from: 'AWAITING_DOCUMENTATION',
    to: ['PENDING_CLASSIFICATION', 'LAPSED', 'WITHDRAWN', 'VOIDED'],
  },
  { from: 'PENDING_EXPERT_REPORT', to: ['PENDING_ANALYST_REVIEW'] },
  { from: 'PENDING_REPAIR', to: ['PENDING_ANALYST_REVIEW'] },
  { from: 'APPROVED', to: ['PENDING_ANALYST_REVIEW'] },
  { from: 'REJECTED', to: ['PENDING_ANALYST_REVIEW'] },
  { from: 'LAPSED', to: ['PENDING_ANALYST_REVIEW'] },
  { from: 'PRESCRIBED', to: ['PENDING_ANALYST_REVIEW'] },
  { from: 'WITHDRAWN', to: ['PENDING_ANALYST_REVIEW'] },
  { from: 'VOIDED', to: ['PENDING_ANALYST_REVIEW'] },
];
