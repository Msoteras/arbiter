import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { ScoringConfigComponent } from './scoring-config.component';
import { ScoringRulesService } from '../scoring-rules.service';

describe('ScoringConfigComponent', () => {
  let fixture: ComponentFixture<ScoringConfigComponent>;

  async function render(
    factors: { factorId: string; weight: number }[],
    readOnly = false,
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ScoringConfigComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ScoringRulesService,
          useValue: {
            get: () => of({ fullAnalysisOnFastTrack: false, factors, bands: [] }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ScoringConfigComponent);
    fixture.componentRef.setInput('readOnly', readOnly);
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function factorRow(label: string): HTMLElement {
    return [...host().querySelectorAll<HTMLElement>('.rule-row')].find((row) =>
      row.querySelector('.rule-text')?.textContent?.includes(label),
    )!;
  }

  function typeWeight(label: string, value: string): void {
    const field = factorRow(label).querySelector('input')!;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function toggle(label: string): void {
    factorRow(label).querySelector<HTMLButtonElement>('[role="switch"]')!.click();
    fixture.detectChanges();
  }

  function text(selector: string): string | undefined {
    return host().querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  }

  function saveButton(): HTMLButtonElement {
    const buttons = host().querySelectorAll<HTMLButtonElement>('app-save-bar button');
    return buttons[buttons.length - 1];
  }

  it('names the weight field after its factor', async () => {
    await render([{ factorId: 'amount_ratio', weight: 0.25 }]);
    const field = factorRow('Relación monto reclamado').querySelector('input')!;
    const label = field.labels![0].cloneNode(true) as HTMLElement;
    label.querySelectorAll('[aria-hidden="true"]').forEach((hidden) => hidden.remove());

    expect(field.value).toBe('25');
    expect(label.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Peso de Relación monto reclamado / suma asegurada',
    );
  });

  it('shows no weight field for a factor that is off', async () => {
    await render([{ factorId: 'amount_ratio', weight: 0.25 }]);
    const row = factorRow('Imagen publicada en la web');

    expect(row.querySelector('input')).toBeNull();
    expect(row.textContent).toContain('No se evalúa');
  });

  it('shows the total of the active weights', async () => {
    await render([
      { factorId: 'amount_ratio', weight: 0.6 },
      { factorId: 'claim_frequency', weight: 0.4 },
    ]);

    expect(text('.sc-total')).toBe('Total: 100%');
    expect(host().querySelector('.sc-total-note')).toBeNull();
  });

  it('blocks the save until the weights add up to 100%', async () => {
    await render([
      { factorId: 'amount_ratio', weight: 0.6 },
      { factorId: 'claim_frequency', weight: 0.4 },
    ]);

    typeWeight('Frecuencia de siniestros', '25');

    expect(text('.sc-total')).toBe('Total: 85%');
    expect(host().querySelector('.sc-total')!.classList).toContain('invalid');
    expect(text('.sc-total-note')).toBe('Los pesos tienen que sumar 100%.');
    expect(saveButton().disabled).toBeTrue();

    typeWeight('Relación monto reclamado', '75');

    expect(text('.sc-total')).toBe('Total: 100%');
    expect(host().querySelector('.sc-total-note')).toBeNull();
    expect(saveButton().disabled).toBeFalse();
  });

  it('asks for a weight when a factor is switched on', async () => {
    await render([{ factorId: 'amount_ratio', weight: 1 }]);

    toggle('Imagen publicada en la web');

    expect(text('.sc-total')).toBe('Total: 100%');
    expect(host().querySelector('.sc-total')!.classList).not.toContain('invalid');
    expect(text('.sc-total-note')).toBe('Cada factor prendido necesita un peso mayor que 0%.');
    expect(saveButton().disabled).toBeTrue();
  });

  it('rejects fractions of a percent', async () => {
    await render([
      { factorId: 'amount_ratio', weight: 0.6 },
      { factorId: 'claim_frequency', weight: 0.4 },
    ]);

    typeWeight('Relación monto reclamado', '59.5');
    typeWeight('Frecuencia de siniestros', '40.5');

    expect(text('.sc-total-note')).toBe('Los pesos van en porcentajes enteros.');
    expect(saveButton().disabled).toBeTrue();
  });

  it('lets the scoring be saved with every factor off', async () => {
    await render([{ factorId: 'amount_ratio', weight: 1 }]);

    toggle('Relación monto reclamado');

    expect(host().querySelector('.sc-total')).toBeNull();
    expect(host().querySelector('.sc-total-note')).toBeNull();
    expect(saveButton().disabled).toBeFalse();
  });

  it('shows the scoring to an analyst with nothing to change or save', async () => {
    await render(
      [
        { factorId: 'amount_ratio', weight: 2 },
        { factorId: 'claim_frequency', weight: 1 },
      ],
      true,
    );

    expect(text('.sc-total')).toBe('Total: 300%');
    expect(host().querySelector('.sc-total-note')).toBeNull();
    expect(host().querySelector('app-save-bar')).toBeNull();
    const controls = [
      ...host().querySelectorAll<HTMLInputElement | HTMLButtonElement>('input, [role="switch"]'),
    ];
    expect(controls.length).toBeGreaterThan(0);
    expect(controls.filter((c) => !c.disabled)).toEqual([]);
  });
});
