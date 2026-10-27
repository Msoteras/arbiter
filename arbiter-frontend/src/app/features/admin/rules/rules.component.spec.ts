import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';

import { RulesComponent } from './rules.component';
import { BranchOption, BranchesService } from '../branches.service';
import { BusinessRulesTextService } from '../business-rules-text.service';
import { CoverageExclusionsService } from '../coverage-exclusions.service';
import { CoverageDetail, CoveragesRulesService } from '../coverages-rules.service';
import { DerivationRulesService } from '../derivation-rules.service';
import { DocumentRulesService } from '../document-rules.service';
import {
  CoverageOption,
  FastTrackConfigDto,
  FastTrackRulesService,
} from '../fast-track-rules.service';
import { HardRule, HardRulesService, InsurerHardRule } from '../hard-rules.service';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { DocumentTypeDef } from '../../../core/models/business-rules';
import { UserRole } from '../../../core/models/user-role';
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
    reportDeadlineHours: 72,
    maxAnnualClaims: 2,
    waitingPeriodDays: 30,
    settlementFormula: 'REPAIR',
    settlementBasis: 'LESSER_OF_SUM_AND_REPLACEMENT',
    secondEventRatio: null,
    deductPendingInstallments: false,
    deductOverdueBalance: false,
    exclusions: [],
    ...over,
  });

  let branchDocuments: Record<number, readonly DocumentTypeDef[]>;
  let agenda: () => Observable<string[]>;
  let branchList: () => Observable<BranchOption[]>;
  let claimCauses: () => Observable<{ id: number; name: string }[]>;
  let hardRules: HardRule[];
  let ftCoverages: CoverageOption[];
  let ftConfig: FastTrackConfigDto | null;
  let textRules: string[];
  let insurerRules: InsurerHardRule[];

  beforeEach(() => {
    branchDocuments = { 1: DOCUMENT_TYPES_FIXTURE, 2: DOCUMENT_TYPES_FIXTURE };
    agenda = () => of([]);
    branchList = () =>
      of([
        { id: 1, name: 'Celulares' },
        { id: 2, name: 'Tecnología Portátil' },
      ]);
    claimCauses = () => of([{ id: 4, name: 'Caída' }]);
    hardRules = [];
    ftCoverages = [];
    ftConfig = null;
    textRules = [];
    insurerRules = [];
  });

  async function mount(
    coverages: CoverageDetail[] = [],
    rol: UserRole = 'REFERENTE_ASEGURADORA',
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [RulesComponent],
      providers: [
        { provide: AuthSessionService, useValue: { session: signal({ rol }) } },
        {
          provide: DocumentTypeService,
          useValue: Object.assign(new DocumentTypeServiceStub(), {
            forBranch: (branchId: number) => of(branchDocuments[branchId] ?? []),
          }),
        },
        provideNoopAnimations(),
        // The first-setup guide reads the route and asks the backend for its progress.
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: BranchesService,
          useValue: { list: () => branchList() },
        },
        {
          provide: CoveragesRulesService,
          useValue: {
            summary: () => of([]),
            listDetailed: () => of(coverages),
            insurerNames: () => of(coverages.map((c) => c.name)),
          },
        },
        {
          provide: CoverageExclusionsService,
          useValue: { listClaimCauses: () => claimCauses(), get: () => of([]) },
        },
        {
          provide: FastTrackRulesService,
          useValue: { listCoverages: () => of(ftCoverages), getFastTrack: () => of(ftConfig) },
        },
        {
          provide: BusinessRulesTextService,
          useValue: { getExclusions: () => of(textRules), getBusinessRules: () => of(textRules) },
        },
        {
          provide: HardRulesService,
          useValue: { getInsurerWide: () => of(insurerRules), get: () => of(hardRules) },
        },
        { provide: DocumentRulesService, useValue: { get: () => agenda() } },
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

  /** The label alone: a tab with a status also carries "(falta configurar)" for screen readers. */
  function labelOf(tab: Element | null | undefined): string | undefined {
    return tab?.textContent?.replace(/\s*\([^)]*\)\s*$/, '').trim();
  }

  function tab(label: string): HTMLButtonElement {
    return [...el().querySelectorAll<HTMLButtonElement>('app-tabs [role="tab"]')].find(
      (t) => labelOf(t) === label,
    )!;
  }

  function activeTab(): string | undefined {
    return labelOf(el().querySelector('app-tabs .tab.active'));
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

  it('shows the report deadline in hours, as stored', async () => {
    await mount([coverage({ reportDeadlineHours: 36 })]);

    expect(el().textContent).toContain('Denuncia 36 h');
  });

  describe('a hard rule of the coverage', () => {
    function ruleRow(label: string): HTMLElement {
      return [...el().querySelectorAll<HTMLElement>('.cov-body .rule-row')].find((row) =>
        row.querySelector('.rule-text')?.textContent?.includes(label),
      )!;
    }

    beforeEach(() => {
      localStorage.removeItem(LAST_TAB_KEY);
      hardRules = [
        { ruleType: 'WAITING_PERIOD', enabled: false, deadlineHours: null },
        { ruleType: 'REPORT_DEADLINE', enabled: true, deadlineHours: null },
        { ruleType: 'MAX_EVENTS_YEAR', enabled: false, deadlineHours: null },
      ];
    });

    it('shows its value only while it is on', async () => {
      await mount([coverage()]);
      click(el().querySelector<HTMLElement>('.cov-toggle'));

      expect(ruleRow('Plazo de denuncia').querySelector('input')?.value).toBe('72');
      expect(ruleRow('Carencia').querySelector('input')).toBeNull();
      expect(ruleRow('Carencia').textContent).toContain('Sin carencia');
      expect(ruleRow('Tope de eventos por año').textContent).toContain('Sin tope');
    });

    it('shows the value again when it is switched on', async () => {
      await mount([coverage()]);
      click(el().querySelector<HTMLElement>('.cov-toggle'));

      click(ruleRow('Carencia').querySelector<HTMLElement>('app-switch button'));

      expect(ruleRow('Carencia').querySelector('input')?.value).toBe('30');
    });

    it('leaves the switched off ones out of the collapsed summary', async () => {
      await mount([coverage()]);

      const summary = el().querySelector('.cov-chips')?.textContent;
      expect(summary).toContain('Denuncia 72 h');
      expect(summary).not.toContain('Carencia');
      expect(summary).not.toContain('Máx.');
    });
  });

  describe('when part of the branch fails to load', () => {
    beforeEach(() => {
      claimCauses = () => throwError(() => new Error('down'));
    });

    it('shows the error instead of an editable, incomplete branch', async () => {
      await mount([coverage()]);

      expect(el().textContent).toContain('No se pudo cargar el ramo.');
      expect(el().querySelector('.cov-card')).toBeNull();
      expect(el().textContent).not.toContain('Agregar cobertura');
    });

    it('loads the branch again on Reintentar', async () => {
      await mount([coverage()]);
      claimCauses = () => of([{ id: 4, name: 'Caída' }]);

      click(
        [...el().querySelectorAll<HTMLButtonElement>('button')].find(
          (b) => b.textContent?.trim() === 'Reintentar',
        ),
      );

      expect(el().textContent).not.toContain('No se pudo cargar el ramo.');
      expect(el().querySelector('.cov-card')).not.toBeNull();
    });
  });

  /** A branch another referent creates shows up without moving the referent off their branch. */
  it('takes a refreshed branch list and keeps the open branch', async () => {
    const lists = new BehaviorSubject<BranchOption[]>([
      { id: 1, name: 'Celulares' },
      { id: 2, name: 'Tecnología Portátil' },
    ]);
    branchList = () => lists;
    await mount();
    click(branch('Tecnología Portátil'));

    lists.next([
      { id: 1, name: 'Celulares' },
      { id: 2, name: 'Tecnología Portátil' },
      { id: 3, name: 'Hogar' },
    ]);
    fixture.detectChanges();

    expect(branch('Hogar')).toBeTruthy();
    expect(el().querySelector('.branch-title')?.textContent).toContain('Tecnología Portátil');
  });

  describe('the Documentación marker', () => {
    function documentsTab(): string | undefined {
      return [...el().querySelectorAll('app-tabs [role="tab"]')]
        .find((t) => labelOf(t) === 'Documentación')
        ?.textContent?.trim();
    }

    /** It used to flash "(incompleto)" for a couple of seconds on every branch. */
    it('stays off while the agenda is still loading', async () => {
      agenda = () => new Subject<string[]>();
      await mount([coverage()]);

      expect(documentsTab()).toBe('Documentación');
    });

    it('turns on once a covered claim cause turns out to require nothing', async () => {
      const arriving = new Subject<string[]>();
      agenda = () => arriving;
      await mount([coverage()]);

      arriving.next([]);
      arriving.complete();
      fixture.detectChanges();

      expect(documentsTab()).toContain('(incompleto)');
    });

    it('stays off when every covered claim cause asks for something', async () => {
      agenda = () => of(['purchase_proof']);
      await mount([coverage()]);

      expect(documentsTab()).toBe('Documentación');
    });
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

  it('offers only the covered claim causes as checkboxes', async () => {
    localStorage.removeItem(LAST_TAB_KEY);
    await mount([coverage()]);

    click(el().querySelector<HTMLElement>('.cov-toggle'));

    const labels = [...el().querySelectorAll('.cov-body .check-list app-checkbox')].map((c) =>
      c.textContent?.trim(),
    );
    expect(labels).toEqual(['Caída']);
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

  function generalSection(label: string): HTMLButtonElement {
    return [...el().querySelectorAll<HTMLButtonElement>('.branch-item.general')].find(
      (b) => b.textContent?.trim() === label,
    )!;
  }

  function generalSectionLabels(): (string | undefined)[] {
    return [...el().querySelectorAll('.branch-item.general')].map((b) => b.textContent?.trim());
  }

  it('lets the referente edit the branch and open Marca', async () => {
    localStorage.removeItem(LAST_TAB_KEY);
    await mount([coverage()]);

    expect(el().querySelector('.rules-head app-badge')).toBeNull();
    expect(el().querySelector('[aria-label="Renombrar el ramo"]')).not.toBeNull();
    expect(el().querySelector('.editor app-save-bar')).not.toBeNull();
    expect(generalSectionLabels()).toContain('Marca');
  });

  describe('for the analyst', () => {
    beforeEach(() => {
      hardRules = [
        { ruleType: 'WAITING_PERIOD', enabled: true, deadlineHours: null },
        { ruleType: 'REPORT_DEADLINE', enabled: true, deadlineHours: null },
        { ruleType: 'MAX_EVENTS_YEAR', enabled: true, deadlineHours: null },
      ];
      ftCoverages = [{ id: 3, name: 'Daño accidental' }];
      ftConfig = {
        maxClaimedAmountRatio: 0.5,
        maxPriorClaims: 1,
        priorClaimsWindowMonths: 12,
        minPolicyAgeMonths: 3,
        requiresUpToDatePolicy: true,
        requiredDocumentTypes: ['police_report'],
        criteria: ['Sin siniestros previos'],
      };
      textRules = ['Uso indebido del equipo'];
      agenda = () => of(['police_report']);
      insurerRules = [
        { ruleType: 'POLICY_IN_FORCE', enabled: true, onArrears: null },
        { ruleType: 'POLICY_STANDING', enabled: true, onArrears: 'REJECT' },
      ];
    });

    function editableControls(): HTMLElement[] {
      return [
        ...el().querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLButtonElement>(
          '.editor input, .editor textarea, .editor [role="switch"], .editor [role="combobox"]',
        ),
      ].filter((control) => !control.disabled && !control.closest('.ft-coverage'));
    }

    function editActions(): string[] {
      return [...el().querySelectorAll<HTMLButtonElement>('.editor button')]
        .map((b) => (b.getAttribute('aria-label') ?? b.textContent ?? '').trim())
        .filter((label) => /Agregar|Quitar|Editar|Renombrar|Guardar|Descartar/.test(label));
    }

    for (const tabId of [
      'coverages',
      'exclusions',
      'fastTrack',
      'documents',
      'derivations',
      'rules',
    ]) {
      it(`shows the ${tabId} tab with nothing to change or save`, async () => {
        localStorage.setItem(LAST_TAB_KEY, tabId);
        await mount([coverage()], 'ANALISTA_SINIESTROS');
        if (tabId === 'coverages') {
          click(el().querySelector<HTMLElement>('.cov-toggle'));
        }

        expect(el().querySelector('.editor app-save-bar')).toBeNull();
        expect(editableControls()).toEqual([]);
        expect(editActions()).toEqual([]);
      });
    }

    it('still shows the values on screen', async () => {
      localStorage.removeItem(LAST_TAB_KEY);
      await mount([coverage()], 'ANALISTA_SINIESTROS');
      click(el().querySelector<HTMLElement>('.cov-toggle'));

      expect(
        el().querySelector<HTMLTextAreaElement>('.cov-body app-textarea textarea')?.value,
      ).toBe('Cobertura por rotura o caída accidental del equipo');
    });

    it('lets the analyst switch the Fast Track coverage to read each one', async () => {
      localStorage.setItem(LAST_TAB_KEY, 'fastTrack');
      await mount([coverage()], 'ANALISTA_SINIESTROS');

      const picker = el().querySelector<HTMLButtonElement>('.ft-coverage [role="combobox"]');
      expect(picker?.disabled).toBeFalse();
    });

    it('labels the screen as read-only and hides the rename pencil', async () => {
      localStorage.removeItem(LAST_TAB_KEY);
      await mount([coverage()], 'ANALISTA_SINIESTROS');

      expect(el().querySelector('.rules-head app-badge')?.textContent?.trim()).toBe('Solo lectura');
      expect(el().querySelector('[aria-label="Renombrar el ramo"]')).toBeNull();
    });

    it('lists every other setting except Marca', async () => {
      await mount([], 'ANALISTA_SINIESTROS');

      expect(generalSectionLabels()).toEqual([
        'Hard Stop',
        'Puntaje de riesgo',
        'Gestión de fraude',
        'Proveedores externos',
        'Atribuciones de liquidación',
        'Objetivo de resolución',
      ]);
    });

    it('shows Hard Stop with nothing to change or save', async () => {
      await mount([], 'ANALISTA_SINIESTROS');
      click(generalSection('Hard Stop'));

      expect(el().querySelectorAll('.editor [role="switch"]').length).toBe(2);
      expect(el().querySelector('.editor app-save-bar')).toBeNull();
      expect(editableControls()).toEqual([]);
    });
  });
});
