import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { HistorialReglasComponent } from './historial-reglas.component';
import { BranchesService } from '../branches.service';
import { RuleChangeEntry, RuleHistoryService } from '../rule-history.service';

/**
 * Covers what the component adds to the backend response: field labels, and telling "nothing
 * changed" apart from "not recorded".
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
    partial: false,
    author: 'Ana Pérez',
    ...over,
  });

  async function mount(entries: RuleChangeEntry[]): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [HistorialReglasComponent],
      providers: [
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
    expect(text()).toContain('image_reuse');
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
    expect(text()).toContain('image_reuse');
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

  /** On a partial row, empty `changes` means "not recorded", never "nothing changed". */
  it('explica el registro parcial en vez de dar a entender que no cambió nada', async () => {
    await mount([entry({ changes: [], partial: true })]);

    expect(text()).toContain('Registro parcial');
    expect(text()).toContain('Registro anterior al detalle campo por campo');
    expect(text()).not.toContain('sin cambios');
  });

  it('traduce los booleanos y marca el valor ausente', async () => {
    await mount([
      entry({ changes: [{ field: 'active', previousValue: 'false', newValue: null }] }),
    ]);

    expect(text()).toContain('Regla activa');
    expect(text()).toContain('No');
    expect(text()).toContain('—');
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
