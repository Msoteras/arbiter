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

  beforeEach(async () => {
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
            get: () =>
              of({
                fullAnalysisOnFastTrack: false,
                factors: [{ factorId: 'amount_ratio', weight: 0.25 }],
                bands: [],
              }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ScoringConfigComponent);
    fixture.detectChanges();
  });

  function factorRow(label: string): HTMLElement {
    return [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.rule-row'),
    ].find((row) => row.querySelector('.rule-text')?.textContent?.includes(label))!;
  }

  it('names the weight field after its factor', () => {
    const field = factorRow('Relación monto reclamado').querySelector('input')!;

    expect(field.value).toBe('25');
    expect(field.labels?.[0].textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Peso de Relación monto reclamado / suma asegurada',
    );
  });

  it('shows no weight field for a factor that is off', () => {
    const row = factorRow('Imagen publicada en la web');

    expect(row.querySelector('input')).toBeNull();
    expect(row.textContent).toContain('No se evalúa');
  });
});
