import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { RulesComponent } from './rules.component';
import { BranchesService } from '../branches.service';
import { BusinessRulesTextService } from '../business-rules-text.service';
import { CoverageExclusionsService } from '../coverage-exclusions.service';
import { CoverageDetail, CoveragesRulesService } from '../coverages-rules.service';
import { DerivationRulesService } from '../derivation-rules.service';
import { DocumentRulesService } from '../document-rules.service';
import { FastTrackRulesService } from '../fast-track-rules.service';
import { HardRulesService } from '../hard-rules.service';
import { DocumentTypeDef } from '../../../core/models/business-rules';
import { DocumentTypeService } from '../../../core/services/document-type.service';
import {
  DOCUMENT_TYPES_FIXTURE,
  DocumentTypeServiceStub,
} from '../../../core/testing/document-types';

const LAST_TAB_KEY = 'arbiter.reglas.lastTab';

/** Covers the screen's own behaviour around tabs and the coverage editor, with every backend mocked. */
describe('RulesComponent', () => {
  let fixture: ComponentFixture<RulesComponent>;

  const coverage = (over: Partial<CoverageDetail> = {}): CoverageDetail => ({
    id: 3,
    name: 'Daño accidental',
    clause: null,
    description: 'Cobertura por rotura o caída accidental del equipo',
    deductibleRatio: 0.2,
    deductibleBasis: 'LOSS_AMOUNT',
    reportingWindowDays: 3,
    maxAnnualClaims: 2,
    waitingPeriodDays: 30,
    coversFamilyGroup: false,
    claimExhaustsCoverage: false,
    settlementFormula: 'REPAIR',
    settlementBasis: 'LESSER_OF_SUM_AND_REPLACEMENT',
    secondEventRatio: null,
    deductPendingInstallments: false,
    deductOverdueBalance: false,
    exclusions: [],
    ...over,
  });

  let branchDocuments: Record<number, readonly DocumentTypeDef[]>;

  beforeEach(() => {
    branchDocuments = { 1: DOCUMENT_TYPES_FIXTURE, 2: DOCUMENT_TYPES_FIXTURE };
  });

  async function mount(coverages: CoverageDetail[] = []): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [RulesComponent],
      providers: [
        {
          provide: DocumentTypeService,
          useValue: Object.assign(new DocumentTypeServiceStub(), {
            forBranch: (branchId: number) => of(branchDocuments[branchId] ?? []),
          }),
        },
        provideNoopAnimations(),
        {
          provide: BranchesService,
          useValue: {
            list: () =>
              of([
                { id: 1, name: 'Celulares' },
                { id: 2, name: 'Tecnología Portátil' },
              ]),
          },
        },
        {
          provide: CoveragesRulesService,
          useValue: { summary: () => of([]), listDetailed: () => of(coverages) },
        },
        {
          provide: CoverageExclusionsService,
          useValue: { listClaimCauses: () => of([{ id: 4, name: 'Caída' }]), get: () => of([]) },
        },
        {
          provide: FastTrackRulesService,
          useValue: { listCoverages: () => of([]), getFastTrack: () => of(null) },
        },
        {
          provide: BusinessRulesTextService,
          useValue: { getExclusions: () => of([]), getBusinessRules: () => of([]) },
        },
        {
          provide: HardRulesService,
          useValue: { getInsurerWide: () => of([]), get: () => of([]) },
        },
        { provide: DocumentRulesService, useValue: { get: () => of([]) } },
        {
          provide: DerivationRulesService,
          useValue: {
            getExpert: () => of({ enabled: false, minClaimedAmount: null }),
            getRepair: () => of({ enabled: false, claimCauseIds: [] }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RulesComponent);
    fixture.detectChanges();
  }

  afterEach(() => localStorage.removeItem(LAST_TAB_KEY));

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function click(target: HTMLElement | null | undefined): void {
    target!.click();
    fixture.detectChanges();
  }

  function tab(label: string): HTMLButtonElement {
    return [...el().querySelectorAll<HTMLButtonElement>('app-tabs [role="tab"]')].find(
      (t) => t.textContent?.trim() === label,
    )!;
  }

  function activeTab(): string | undefined {
    return el().querySelector('app-tabs .tab.active')?.textContent?.trim();
  }

  function branch(name: string): HTMLButtonElement {
    return [...el().querySelectorAll<HTMLButtonElement>('.branch-item:not(.general)')].find((b) =>
      b.textContent?.includes(name),
    )!;
  }

  it('opens the first branch on Coberturas when nothing was stored', async () => {
    localStorage.removeItem(LAST_TAB_KEY);
    await mount();

    expect(el().querySelector('.branch-title')?.textContent).toContain('Celulares');
    expect(activeTab()).toBe('Coberturas');
  });

  it('opens on the tab left open in the last visit', async () => {
    localStorage.setItem(LAST_TAB_KEY, 'documents');
    await mount();

    expect(activeTab()).toBe('Documentación');
  });

  it('ignores a stored tab that no longer exists', async () => {
    localStorage.setItem(LAST_TAB_KEY, 'gone');
    await mount();

    expect(activeTab()).toBe('Coberturas');
  });

  it('remembers the tab the referente picks', async () => {
    await mount();

    click(tab('Fast Track'));

    expect(localStorage.getItem(LAST_TAB_KEY)).toBe('fastTrack');
  });

  it('lists in the agenda only the documents the branch may ask for', async () => {
    branchDocuments[2] = [
      { code: 'purchase_proof', label: 'Comprobante de compra' },
      { code: 'item_photo', label: 'Foto del bien' },
    ];
    localStorage.setItem(LAST_TAB_KEY, 'documents');
    await mount();

    click(branch('Tecnología Portátil'));

    const rows = [...el().querySelectorAll('.doc-row-name')].map((r) => r.textContent?.trim());
    expect(rows).toEqual(['Comprobante de compra', 'Foto del bien']);
  });

  it('says so when the branch has no documents enabled', async () => {
    branchDocuments[1] = [];
    localStorage.setItem(LAST_TAB_KEY, 'documents');
    await mount();

    expect(el().textContent).toContain('Este ramo todavía no tiene documentos habilitados.');
    expect(el().querySelector('.doc-row-name')).toBeNull();
  });

  /** It used to jump back to Coberturas on every branch, which made the stored tab useless. */
  it('keeps the tab when another branch is opened', async () => {
    await mount();
    click(tab('Fast Track'));

    click(branch('Tecnología Portátil'));

    expect(el().querySelector('.branch-title')?.textContent).toContain('Tecnología Portátil');
    expect(activeTab()).toBe('Fast Track');
  });

  it('keeps the derivations tab mounted but hidden while another tab shows', async () => {
    await mount();

    const derivations = el().querySelector<HTMLElement>('app-derivations-config')!;
    expect(derivations.hidden).toBeTrue();

    click(tab('Derivaciones'));

    expect(derivations.hidden).toBeFalse();
  });

  it('shows the description of a coverage when it is expanded', async () => {
    localStorage.removeItem(LAST_TAB_KEY);
    await mount([coverage()]);

    click(el().querySelector<HTMLElement>('.cov-toggle'));

    expect(el().querySelector<HTMLTextAreaElement>('.cov-body app-textarea textarea')?.value).toBe(
      'Cobertura por rotura o caída accidental del equipo',
    );
  });

  it('offers the coverage flags and the covered claim causes as checkboxes', async () => {
    localStorage.removeItem(LAST_TAB_KEY);
    await mount([coverage()]);

    click(el().querySelector<HTMLElement>('.cov-toggle'));

    const labels = [...el().querySelectorAll('.cov-body .check-list app-checkbox')].map((c) =>
      c.textContent?.trim(),
    );
    expect(labels).toEqual([
      'Alcanza al grupo familiar',
      'Un siniestro agota la cobertura',
      'Caída',
    ]);
  });

  it('marks the coverages as changed once the description is edited', async () => {
    localStorage.removeItem(LAST_TAB_KEY);
    await mount([coverage()]);
    click(el().querySelector<HTMLElement>('.cov-toggle'));

    const field = el().querySelector<HTMLTextAreaElement>('.cov-body app-textarea textarea')!;
    field.value = 'Rotura de pantalla';
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(el().querySelector('.tab-panel app-save-bar')?.textContent).toContain(
      'Cambios sin guardar',
    );
  });
});
