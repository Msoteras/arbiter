import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { HistorialReglasComponent } from './historial-reglas.component';
import { BranchesService } from '../branches.service';
import { RuleChangeEntry, RuleHistoryService } from '../rule-history.service';

/**
 * Covers what the component adds to the backend response: field labels, list diffs, and the
 * creation entry.
 */
describe('HistorialReglasComponent', () => {
  let fixture: ComponentFixture<HistorialReglasComponent>;

  const entry = (over: Partial<RuleChangeEntry> = {}): RuleChangeEntry => ({
    id: 'rule-1',
    source: 'INSURER_RULE',
    ruleType: 'POLICE_DEADLINE',
    ruleName: 'Plazo de la denuncia policial',
    branchId: 1,
    branchName: 'Celulares',
    coverageId: 2,
    coverageName: 'Robo de celular',
    changedAt: '2026-09-01T14:00:00Z',
    previousValidFrom: '2026-08-01T14:00:00Z',
    reason: 'Fast Track actualizado por ana@bbva.com',
    changes: [{ field: 'deadlineHours', previousValue: '72', newValue: '120' }],
    current: true,
    kind: 'UPDATED',
    author: 'Ana Pérez',
    ...over,
  });

  async function mount(entries: RuleChangeEntry[]): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [HistorialReglasComponent],
      providers: [
        provideNoopAnimations(),
        {
          provide: RuleHistoryService,
          useValue: {
            find: () =>
              of({
                content: entries,
                totalElements: entries.length,
                totalPages: 1,
                number: 0,
                size: 20,
              }),
            ruleTypes: () => of(['POLICE_DEADLINE']),
          },
        },
        { provide: BranchesService, useValue: { list: () => of([{ id: 1, name: 'Celulares' }]) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(HistorialReglasComponent);
    fixture.detectChanges();
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  it('muestra el campo con su etiqueta en español, no con la clave del JSON', async () => {
    await mount([entry()]);

    expect(text()).toContain('Plazo en horas');
    expect(text()).not.toContain('deadlineHours');
    expect(text()).toContain('72');
    expect(text()).toContain('120');
  });

  /** Unlabeled fields fall back to the path's last segment; the factor code travels as qualifier. */
  it('sin etiqueta cae al último tramo de la ruta y separa el calificador', async () => {
    await mount([
      entry({
        ruleType: 'SCORING',
        changes: [{ field: 'factors[image_reuse].sinEtiqueta', previousValue: '1', newValue: '2' }],
      }),
    ]);

    expect(text()).toContain('sinEtiqueta');
    expect(text()).toContain('Imagen reutilizada de otra denuncia');
    expect(text()).not.toContain('factors[image_reuse].sinEtiqueta');
  });

  it('usa la etiqueta del scoring y su calificador cuando la conoce', async () => {
    await mount([
      entry({
        ruleType: 'SCORING',
        changes: [{ field: 'factors[image_reuse].weight', previousValue: '0.2', newValue: '0.4' }],
      }),
    ]);

    expect(text()).toContain('Peso');
    expect(text()).toContain('20%');
    expect(text()).toContain('40%');
    expect(text()).toContain('Imagen reutilizada de otra denuncia');
    expect(text()).not.toContain('image_reuse');
  });

  it('muestra la banda de riesgo con su nombre, no con el literal del enum', async () => {
    await mount([
      entry({
        ruleType: 'SCORING',
        changes: [
          { field: 'bands[HIGH].minScoreInclusive', previousValue: '0.6', newValue: '0.7' },
        ],
      }),
    ]);

    expect(text()).toContain('Alto');
    expect(text()).toContain('60%');
    expect(text()).not.toContain('HIGH');
  });

  it('nombra el objetivo de resolución y sus campos', async () => {
    await mount([
      entry({
        ruleType: 'RESOLUTION_TARGET',
        changes: [
          { field: 'targetDays', previousValue: '22', newValue: '21' },
          { field: 'enabled', previousValue: 'false', newValue: 'true' },
        ],
      }),
    ]);

    expect(text()).toContain('Objetivo de resolución');
    expect(text()).toContain('Días objetivo de resolución');
    expect(text()).toContain('Objetivo habilitado');
    expect(text()).not.toContain('RESOLUTION_TARGET');
    expect(text()).not.toContain('Puntaje habilitado');
  });

  it('traduce qué hacer ante mora en vez de mostrar el literal', async () => {
    await mount([
      entry({
        ruleType: 'POLICY_STANDING',
        changes: [{ field: 'onArrears', previousValue: 'STANDBY', newValue: 'REJECT' }],
      }),
    ]);

    expect(text()).toContain('Rechazar en el alta');
    expect(text()).not.toContain('STANDBY');
  });

  it('no repite la clave del factor como un campo más', async () => {
    await mount([
      entry({
        ruleType: 'SCORING',
        changes: [
          {
            field: 'factors[fraud_history].factorId',
            previousValue: null,
            newValue: 'fraud_history',
          },
          { field: 'factors[fraud_history].weight', previousValue: null, newValue: '0.6' },
        ],
      }),
    ]);

    expect(fixture.nativeElement.querySelectorAll('.hr-field').length).toBe(1);
    expect(text()).not.toContain('fraud_history');
  });

  /** Lists are diffed item by item: only what left and what came in, the rest is counted. */
  it('en un campo de lista muestra solo lo que se quitó y lo que se agregó', async () => {
    await mount([
      entry({
        ruleType: 'FAST_TRACK',
        changes: [
          {
            field: 'criteria',
            previousValue: 'Primer siniestro · Monto inferior al 30% · Póliza al día',
            newValue: 'Monto inferior al 50% · Póliza al día',
          },
        ],
      }),
    ]);

    const removed = fixture.nativeElement.querySelectorAll('.is-removed');
    const added = fixture.nativeElement.querySelectorAll('.is-added');
    expect(removed.length).toBe(2);
    expect(removed[0].textContent).toContain('Se quitó');
    expect(removed[0].textContent).toContain('Primer siniestro');
    expect(added.length).toBe(1);
    expect(added[0].textContent).toContain('Se agregó');
    expect(added[0].textContent).toContain('Monto inferior al 50%');
    expect(text()).toContain('1 ítem sin cambios');
  });

  it('muestra la documentación exigida con sus nombres, no con los códigos', async () => {
    await mount([
      entry({
        ruleType: 'FAST_TRACK',
        changes: [
          {
            field: 'requiredDocumentTypes',
            previousValue: 'police_report · purchase_proof',
            newValue: 'purchase_proof · repair_quote',
          },
        ],
      }),
    ]);

    expect(text()).toContain('Denuncia policial');
    expect(text()).toContain('Presupuesto de reparación');
    expect(text()).not.toContain('police_report');
    expect(text()).not.toContain('repair_quote');
  });

  /** The reason's prose isn't shown: what changed is already in the title and the scope. */
  it('muestra el autor que resuelve el back, no el motivo entero', async () => {
    await mount([entry({ reason: 'Hard rule POLICE_DEADLINE updated by ana@bbva.com' })]);

    expect(text()).toContain('por Ana Pérez');
    expect(text()).not.toContain('Hard rule');
    expect(text()).not.toContain('updated by');
  });

  it('no deja un "por" colgado cuando el motivo no nombra a nadie', async () => {
    await mount([entry({ reason: 'Actualización automática', author: null })]);

    expect(text()).not.toContain('Actualización automática');
    expect(text()).not.toMatch(/por\s*$/);
  });

  /** A creation has nothing to compare: it says so, without a previous version or a diff. */
  it('muestra la creación de la regla sin versión anterior', async () => {
    await mount([entry({ kind: 'CREATED', changes: [], previousValidFrom: null, author: null })]);

    expect(text()).toContain('Se creó la regla');
    expect(fixture.nativeElement.querySelector('.hr-values')).toBeNull();
    expect(fixture.nativeElement.querySelector('.hr-fields')).toBeNull();
  });

  it('traduce los booleanos', async () => {
    await mount([
      entry({ changes: [{ field: 'active', previousValue: 'true', newValue: 'false' }] }),
    ]);

    expect(text()).toContain('Regla activa');
    expect(text()).toContain('Sí');
    expect(text()).toContain('No');
  });

  /** A value present on one side only was added or removed, worded like the list items. */
  it('dice "Se agregó" cuando el campo no existía en la versión anterior', async () => {
    await mount([
      entry({
        ruleType: 'SCORING',
        changes: [{ field: 'factors[fraud_history].weight', previousValue: null, newValue: '0.6' }],
      }),
    ]);

    const added = fixture.nativeElement.querySelectorAll('.is-added');
    expect(added.length).toBe(1);
    expect(added[0].textContent).toContain('Se agregó');
    expect(added[0].textContent).toContain('60%');
    expect(text()).not.toContain('→');
    expect(text()).not.toContain('Sin definir');
  });

  it('muestra el alcance de una regla de toda la aseguradora en vez de dejarlo vacío', async () => {
    await mount([
      entry({ branchId: null, branchName: null, coverageId: null, coverageName: null }),
    ]);

    expect(text()).toContain('Toda la aseguradora');
  });

  it('avisa cuando no hay ningún cambio registrado', async () => {
    await mount([]);

    expect(text()).toContain('Todavía no se registraron cambios');
  });
});
