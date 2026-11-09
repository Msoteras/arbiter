import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';

import { RulesComponent } from './rules.component';
import { BranchOption, BranchesService } from '../branches.service';
import { BusinessRulesTextService } from '../business-rules-text.service';
import { CoverageExclusionsService } from '../coverage-exclusions.service';
import { CoverageDetail, CoveragesRulesService } from '../coverages-rules.service';
import { DerivationRulesService } from '../derivation-rules.service';
import { DocumentRulesService } from '../document-rules.service';
import { FastTrackConfigDto, FastTrackRulesService } from '../fast-track-rules.service';
import { HardRule, HardRulesService, InsurerHardRule } from '../hard-rules.service';
import { SettlementAuthoritiesService } from '../settlement-authorities.service';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { DocumentTypeDef } from '../../../core/models/business-rules';
import { UserRole } from '../../../core/models/user-role';
import { DocumentTypeService } from '../../../core/services/document-type.service';
import {
  DOCUMENT_TYPES_FIXTURE,
  DocumentTypeServiceStub,
} from '../../../core/testing/document-types';

/** The backend answers this when a coverage has no Fast Track stored. */
const NO_FAST_TRACK: FastTrackConfigDto = {
  maxClaimedAmountRatio: null,
  maxPriorClaims: null,
  priorClaimsWindowMonths: null,
  minPolicyAgeMonths: null,
  requiresUpToDatePolicy: null,
  requiredDocumentTypes: [],
  criteria: [],
};

/** Covers the screen's levels, tabs and editors, with every backend mocked. */
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
  let agenda: () => Observable<{ documentType: string; minRiskBand: string | null }[]>;
  let branchList: () => Observable<BranchOption[]>;
  let claimCauses: () => Observable<{ id: number; name: string }[]>;
  let excluded: Record<number, number[]>;
  let hardRules: HardRule[];
  let ftConfig: FastTrackConfigDto | null;
  let textRules: string[];
  let insurerRules: InsurerHardRule[];
  let queryParams: Record<string, string>;

  beforeEach(() => {
    branchDocuments = { 1: DOCUMENT_TYPES_FIXTURE, 2: DOCUMENT_TYPES_FIXTURE };
    agenda = () => of([]);
    branchList = () =>
      of([
        { id: 1, name: 'Celulares' },
        { id: 2, name: 'Tecnología Portátil' },
      ]);
    claimCauses = () => of([{ id: 4, name: 'Caída' }]);
    excluded = {};
    hardRules = [];
    ftConfig = null;
    textRules = [];
    insurerRules = [];
    queryParams = {};
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
        {
          provide: ActivatedRoute,
          useFactory: () => ({
            snapshot: { queryParamMap: convertToParamMap(queryParams) },
            queryParamMap: of(convertToParamMap(queryParams)),
          }),
        },
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
          useValue: {
            listClaimCauses: () => claimCauses(),
            get: (coverageId: number) => of(excluded[coverageId] ?? []),
          },
        },
        {
          provide: FastTrackRulesService,
          useValue: {
            listCoverages: () => of(coverages.map((c) => ({ id: c.id, name: c.name }))),
            getFastTrack: () => of(ftConfig),
          },
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
        {
          provide: SettlementAuthoritiesService,
          useValue: {
            list: () =>
              of([
                { branchId: 1, branchName: 'Celulares', maxAmount: 600000, updatedAt: null },
                {
                  branchId: 2,
                  branchName: 'Tecnología Portátil',
                  maxAmount: null,
                  updatedAt: null,
                },
              ]),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RulesComponent);
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function click(target: HTMLElement | null | undefined): void {
    target!.click();
    fixture.detectChanges();
  }

  function text(node: Element | null | undefined): string {
    return node?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  /** The label alone: a tab with a status also carries "(falta configurar)" for screen readers. */
  function labelOf(node: Element | null | undefined): string {
    return text(node).replace(/\s*\([^)]*\)\s*$/, '');
  }

  function tab(label: string): HTMLButtonElement {
    return [...el().querySelectorAll<HTMLButtonElement>('app-tabs [role="tab"]')].find(
      (t) => labelOf(t) === label,
    )!;
  }

  function tabLabels(): string[] {
    return [...el().querySelectorAll('app-tabs [role="tab"]')].map((t) => labelOf(t));
  }

  function activeTab(): string {
    return labelOf(el().querySelector('app-tabs .tab.active'));
  }

  /** A tree item by its label: an insurer section, a branch or a coverage. */
  function treeItem(label: string): HTMLButtonElement {
    return [...el().querySelectorAll<HTMLButtonElement>('app-rules-tree .item')].find(
      (b) => labelOf(b.querySelector('.label')) === label,
    )!;
  }

  function sectionLabels(): string[] {
    const group = el().querySelector('app-rules-tree .group')!;
    return [...group.querySelectorAll('.item .label')].map((l) => text(l));
  }

  function title(): string {
    return text(el().querySelector('.level-title .branch-title'));
  }

  function levelBadge(): string {
    return text(el().querySelector('.level-title app-badge'));
  }

  function button(label: string): HTMLButtonElement {
    return [...el().querySelectorAll<HTMLButtonElement>('button')].find((b) => text(b) === label)!;
  }

  function iconButton(label: string): HTMLButtonElement {
    return el().querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;
  }

  /** A tab's first render waits a painted frame (see `tabRendering`). */
  async function settle(): Promise<void> {
    await new Promise((resolve) => requestAnimationFrame(() => setTimeout(resolve)));
    fixture.detectChanges();
  }

  async function openTab(label: string): Promise<void> {
    click(tab(label));
    await settle();
  }

  function openCoverage(name = 'Daño accidental'): void {
    click(treeItem(name));
  }

  it('opens the first branch on its summary', async () => {
    await mount([coverage()]);

    expect(title()).toBe('Celulares');
    expect(levelBadge()).toBe('Ramo');
    expect(activeTab()).toBe('Resumen');
    expect(tabLabels()).toEqual([
      'Resumen',
      'Exclusiones comunes',
      'Agenda documental',
      'Derivaciones',
      'Reglas de negocio',
      'Atribuciones',
    ]);
  });

  it('lists the coverages of the open branch in the tree', async () => {
    await mount([coverage()]);

    const branch = treeItem('Celulares').closest('li')!;
    expect(branch.querySelector('.caret')?.getAttribute('aria-expanded')).toBe('true');
    expect(treeItem('Daño accidental')).toBeTruthy();
    expect(treeItem('Celulares').getAttribute('aria-current')).toBe('page');
  });

  it('opens a coverage on its own tabs, with the path back to its branch', async () => {
    await mount([coverage()]);

    openCoverage();

    expect(title()).toBe('Daño accidental');
    expect(levelBadge()).toBe('Cobertura');
    expect(tabLabels()).toEqual([
      'Datos',
      'Hechos generadores',
      'Plazos y topes',
      'Liquidación',
      'Fast Track',
    ]);
    expect(
      [...el().querySelectorAll('.crumbs li')].map((li) => text(li).replace(/\s*›$/, '')),
    ).toEqual(['Ramos', 'Celulares', 'Daño accidental']);
    expect(text(el().querySelector('.inherited'))).toContain('También aplica, del ramo Celulares:');

    click(el().querySelector<HTMLElement>('.crumb-link'));

    expect(title()).toBe('Celulares');
    expect(activeTab()).toBe('Resumen');
  });

  it('opens the branch tab a link of the inherited strip names', async () => {
    await mount([coverage()]);
    openCoverage();

    click(button('Agenda documental'));

    expect(levelBadge()).toBe('Ramo');
    expect(activeTab()).toBe('Agenda documental');
  });

  it('keeps the coverage tab when another coverage is picked', async () => {
    await mount([coverage(), coverage({ id: 5, name: 'Robo' })]);
    openCoverage();
    await openTab('Liquidación');

    openCoverage('Robo');

    expect(title()).toBe('Robo');
    expect(activeTab()).toBe('Liquidación');
  });

  it('opens a coverage from the summary card', async () => {
    await mount([coverage()]);

    click(iconButton('Configurar la cobertura'));

    expect(title()).toBe('Daño accidental');
    expect(activeTab()).toBe('Datos');
  });

  describe('the summary map', () => {
    beforeEach(() => {
      claimCauses = () =>
        of([
          { id: 4, name: 'Caída' },
          { id: 6, name: 'Robo' },
          { id: 7, name: 'Incendio' },
        ]);
      // Two coverages that both attend Caída; nothing attends Incendio.
      excluded = { 3: [6, 7], 5: [7] };
    });

    function row(cause: string): HTMLTableRowElement {
      return [...el().querySelectorAll<HTMLTableRowElement>('tbody tr')].find((r) =>
        text(r.querySelector('th')).startsWith(cause),
      )!;
    }

    function cells(cause: string): string[] {
      return [...row(cause).querySelectorAll('td')].map((td) =>
        text(td.querySelector('app-badge, .sr-only')),
      );
    }

    it('marks every coverage that covers a claim cause', async () => {
      await mount([coverage(), coverage({ id: 5, name: 'Robo y daño' })]);

      expect(cells('Caída')).toEqual(['Cubierto', 'Cubierto']);
      expect(cells('Robo')).toEqual(['No cubierto', 'Cubierto']);
    });

    it('flags a claim cause no coverage covers', async () => {
      await mount([coverage(), coverage({ id: 5, name: 'Robo y daño' })]);

      expect(text(row('Incendio').querySelector('th'))).toContain('Sin cobertura');
      expect(text(row('Caída').querySelector('th'))).not.toContain('Sin cobertura');
    });
  });

  it('offers the first coverage of an empty branch as its one action', async () => {
    await mount([]);

    expect(text(el().querySelector('.editor app-empty-state'))).toContain(
      'Este ramo no tiene coberturas',
    );

    click(button('Agregar la primera cobertura'));

    expect(levelBadge()).toBe('Cobertura');
    expect(activeTab()).toBe('Datos');
    expect(el().querySelector('.tab-panel app-save-bar')?.textContent).toContain(
      'Cambios sin guardar',
    );
  });

  describe('the document agenda', () => {
    function cellValue(): string {
      return text(el().querySelector('.doc-cell .trigger-label'));
    }

    it('lists only the documents the branch may ask for', async () => {
      branchDocuments[2] = [
        { code: 'purchase_proof', label: 'Comprobante de compra' },
        { code: 'item_photo', label: 'Foto del bien' },
      ];
      await mount();

      click(treeItem('Tecnología Portátil'));
      await openTab('Agenda documental');

      const rows = [...el().querySelectorAll('.doc-row-name')].map((r) => text(r));
      expect(rows).toEqual(['Comprobante de compra', 'Foto del bien']);
    });

    it('says so when the branch has no documents enabled', async () => {
      branchDocuments[1] = [];
      await mount();
      await openTab('Agenda documental');

      expect(el().textContent).toContain('Este ramo todavía no tiene documentos habilitados.');
      expect(el().querySelector('.doc-row-name')).toBeNull();
    });

    it('reads a document without band as asked always', async () => {
      branchDocuments[1] = [{ code: 'police_report', label: 'Denuncia policial' }];
      agenda = () => of([{ documentType: 'police_report', minRiskBand: null }]);
      await mount();
      await openTab('Agenda documental');

      expect(cellValue()).toBe('Siempre');
      expect(el().querySelector('.doc-cell.is-off')).toBeNull();
    });

    it('reads a band as the risk the document is asked from', async () => {
      branchDocuments[1] = [{ code: 'police_report', label: 'Denuncia policial' }];
      agenda = () => of([{ documentType: 'police_report', minRiskBand: 'HIGH' }]);
      await mount();
      await openTab('Agenda documental');

      expect(cellValue()).toBe('Riesgo alto o más');
    });

    it('mutes a document that is not asked for', async () => {
      branchDocuments[1] = [{ code: 'police_report', label: 'Denuncia policial' }];
      await mount();
      await openTab('Agenda documental');

      expect(cellValue()).toBe('No se pide');
      expect(el().querySelector('.doc-cell.is-off')).not.toBeNull();
      expect(text(el().querySelector('.doc-col-count'))).toBe('Sin documentos');
    });
  });

  it('shows the report deadline in hours on the summary', async () => {
    await mount([coverage({ reportDeadlineHours: 36 })]);

    expect(text(el().querySelector('.cov-chips'))).toContain('Denuncia 36 h');
  });

  describe('a hard rule of the coverage', () => {
    function ruleRow(label: string): HTMLElement {
      return [...el().querySelectorAll<HTMLElement>('app-rule-block')].find((block) =>
        block.textContent?.includes(label),
      )!;
    }

    beforeEach(() => {
      hardRules = [
        { ruleType: 'WAITING_PERIOD', enabled: false, deadlineHours: null },
        { ruleType: 'REPORT_DEADLINE', enabled: true, deadlineHours: null },
        { ruleType: 'MAX_EVENTS_YEAR', enabled: false, deadlineHours: null },
      ];
    });

    async function openTerms(): Promise<void> {
      await mount([coverage()]);
      openCoverage();
      await openTab('Plazos y topes');
    }

    it('shows its value only while it is on', async () => {
      await openTerms();

      expect(ruleRow('Plazo de denuncia').querySelector('input')?.value).toBe('72');
      expect(ruleRow('Carencia').querySelector('input')).toBeNull();
      expect(ruleRow('Carencia').textContent).toContain('Sin carencia');
      expect(ruleRow('Tope de eventos por año').textContent).toContain('Sin tope');
    });

    it('shows the value again when it is switched on', async () => {
      await openTerms();

      click(ruleRow('Carencia').querySelector<HTMLElement>('app-switch button'));

      expect(ruleRow('Carencia').querySelector('input')?.value).toBe('30');
    });

    /** Only the waiting period stops the claim at intake; each group says its own effect. */
    it('puts the waiting period in its own group', async () => {
      await openTerms();

      const groups = [...el().querySelectorAll('app-rule-group')].map((g) => ({
        heading: text(g.querySelector('h3')),
        rules: [...g.querySelectorAll('app-rule-block')].length,
      }));
      expect(groups).toEqual([
        { heading: 'Si no se cumple, el asegurado no puede hacer la denuncia', rules: 1 },
        {
          heading: 'Si no se cumple, la denuncia no es Fast Track y el analista ve el motivo',
          rules: 2,
        },
      ]);
    });

    it('leaves the switched off ones out of the summary', async () => {
      await mount([coverage()]);

      const summary = text(el().querySelector('.cov-chips'));
      expect(summary).toContain('Denuncia 72 h');
      expect(summary).not.toContain('Carencia');
      expect(summary).not.toContain('Máx.');
    });
  });

  describe('the Fast Track conditions', () => {
    beforeEach(() => {
      ftConfig = { ...NO_FAST_TRACK, maxPriorClaims: 0, requiresUpToDatePolicy: true };
    });

    async function openFastTrack(): Promise<void> {
      await mount([coverage()]);
      openCoverage();
      await openTab('Fast Track');
    }

    function blocks(): string[] {
      return [...el().querySelectorAll('app-rule-block')].map((b) => text(b));
    }

    function saveButton(): HTMLButtonElement {
      return [...el().querySelectorAll<HTMLButtonElement>('app-save-bar button')].find((b) =>
        text(b).startsWith('Guardar'),
      )!;
    }

    it('shows one block per active condition, in one list', async () => {
      await openFastTrack();

      expect(text(el().querySelector('app-rule-group h3'))).toBe(
        'Es Fast Track si cumple todas las condiciones',
      );
      expect(blocks().some((b) => b.startsWith('Hasta'))).toBeTrue();
      expect(blocks()).toContain('Póliza al día con sus pagos');
      expect(blocks().some((b) => b.startsWith('Monto reclamado'))).toBeFalse();
    });

    /** With no condition the engine would make every claim Fast Track. */
    it("doesn't save once every condition is removed", async () => {
      await openFastTrack();

      for (const remove of [
        ...el().querySelectorAll<HTMLButtonElement>(
          'app-rule-block button[aria-label="Quitar condición"]',
        ),
      ]) {
        click(remove);
      }

      expect(el().textContent).toContain('Agregá al menos una condición.');
      expect(saveButton().disabled).toBeTrue();
    });

    it('keeps a cleared condition on screen and asks for its value', async () => {
      await openFastTrack();
      const prior = [...el().querySelectorAll<HTMLElement>('app-rule-block')].find((b) =>
        text(b).startsWith('Hasta'),
      )!;
      const input = prior.querySelector<HTMLInputElement>('input')!;

      input.value = '';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      expect(text(prior)).toContain('Escribí un valor o quitá la condición.');
      expect(saveButton().disabled).toBeTrue();
    });

    it('shows the coverage terms as a locked condition while one is on', async () => {
      hardRules = [{ ruleType: 'REPORT_DEADLINE', enabled: true, deadlineHours: null }];
      await openFastTrack();

      expect(blocks()).toContain('Cumple los plazos y topes de la cobertura Plazos y topes');
    });
  });

  describe('Hard Stop', () => {
    async function openHardStop(): Promise<void> {
      await mount();
      click(treeItem('Hard Stop'));
    }

    it('shows the policy term with its fixed effect', async () => {
      insurerRules = [
        { ruleType: 'POLICY_IN_FORCE', enabled: false, onArrears: null },
        { ruleType: 'POLICY_STANDING', enabled: false, onArrears: 'STANDBY' },
      ];
      await openHardStop();

      const [term, arrears] = [...el().querySelectorAll('app-rule-block')].map((b) => text(b));
      expect(term).toContain('Póliza no vigente');
      expect(term).toContain('No deja denunciar');
      expect(arrears).toContain('No controlar');
    });

    it('shows the arrears choice by its effect on the claim', async () => {
      insurerRules = [
        { ruleType: 'POLICY_IN_FORCE', enabled: true, onArrears: null },
        { ruleType: 'POLICY_STANDING', enabled: true, onArrears: 'REJECT' },
      ];
      await openHardStop();

      expect(text(el().querySelectorAll('app-rule-block')[1])).toContain('No dejar denunciar');
    });
  });

  describe('when part of the branch fails to load', () => {
    beforeEach(() => {
      claimCauses = () => throwError(() => new Error('down'));
    });

    it('shows the error instead of an editable, incomplete branch', async () => {
      await mount([coverage()]);

      expect(el().textContent).toContain('No se pudo cargar el ramo.');
      expect(el().querySelector('.cov-grid')).toBeNull();
    });

    it('loads the branch again on Reintentar', async () => {
      await mount([coverage()]);
      claimCauses = () => of([{ id: 4, name: 'Caída' }]);

      click(button('Reintentar'));

      expect(el().textContent).not.toContain('No se pudo cargar el ramo.');
      expect(el().querySelector('.cov-grid')).not.toBeNull();
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
    click(treeItem('Tecnología Portátil'));

    lists.next([
      { id: 1, name: 'Celulares' },
      { id: 2, name: 'Tecnología Portátil' },
      { id: 3, name: 'Hogar' },
    ]);
    fixture.detectChanges();

    expect(treeItem('Hogar')).toBeTruthy();
    expect(title()).toBe('Tecnología Portátil');
  });

  describe('the Agenda documental marker', () => {
    function documentsTab(): string {
      return text(tab('Agenda documental'));
    }

    /** It used to flash "(incompleto)" for a couple of seconds on every branch. */
    it('stays off while the agenda is still loading', async () => {
      agenda = () => new Subject();
      await mount([coverage()]);

      expect(documentsTab()).toBe('Agenda documental');
    });

    it('turns on once a covered claim cause turns out to require nothing', async () => {
      const arriving = new Subject<{ documentType: string; minRiskBand: string | null }[]>();
      agenda = () => arriving;
      await mount([coverage()]);

      arriving.next([]);
      arriving.complete();
      fixture.detectChanges();

      expect(documentsTab()).toContain('(incompleto)');
    });

    it('stays off when every covered claim cause asks for something', async () => {
      agenda = () => of([{ documentType: 'purchase_proof', minRiskBand: null }]);
      await mount([coverage()]);

      expect(documentsTab()).toBe('Agenda documental');
    });
  });

  describe('a coverage without Fast Track', () => {
    beforeEach(() => {
      ftConfig = NO_FAST_TRACK;
    });

    it('is flagged in the tree, on its card and on its tab', async () => {
      await mount([coverage()]);

      expect(text(treeItem('Daño accidental'))).toContain('(falta configurar)');
      expect(text(el().querySelector('.cov-grid app-card'))).toContain('Incompleta');

      openCoverage();

      expect(text(tab('Fast Track'))).toContain('(falta configurar)');
    });

    it('is the one a Fast Track link from the checklist opens', async () => {
      queryParams = { ramo: '1', solapa: 'fastTrack' };
      await mount([coverage()]);
      await settle();

      expect(title()).toBe('Daño accidental');
      expect(activeTab()).toBe('Fast Track');
      expect(el().querySelector('app-card[appOnboardingTarget="FAST_TRACK"]')).not.toBeNull();
    });
  });

  it('is not flagged once the coverage has its Fast Track', async () => {
    ftConfig = { ...NO_FAST_TRACK, requiresUpToDatePolicy: true };
    await mount([coverage()]);

    expect(text(treeItem('Daño accidental'))).not.toContain('(falta configurar)');
  });

  it('keeps the derivations and authorities mounted but hidden while another tab shows', async () => {
    await mount();

    const derivations = el().querySelector<HTMLElement>('app-derivations-config')!;
    const authorities = el().querySelector<HTMLElement>('app-settlement-authorities-config')!;
    expect(derivations.hidden).toBeTrue();
    expect(authorities.hidden).toBeTrue();

    await openTab('Derivaciones');
    expect(derivations.hidden).toBeFalse();

    await openTab('Atribuciones');
    expect(authorities.hidden).toBeFalse();
    expect(derivations.hidden).toBeTrue();
  });

  it('shows the description of the coverage in Datos', async () => {
    await mount([coverage()]);
    openCoverage();

    expect(el().querySelector<HTMLTextAreaElement>('app-textarea textarea')?.value).toBe(
      'Cobertura por rotura o caída accidental del equipo',
    );
  });

  it('offers the claim causes of the branch as switches, on when covered', async () => {
    claimCauses = () =>
      of([
        { id: 4, name: 'Caída' },
        { id: 6, name: 'Robo' },
      ]);
    excluded = { 3: [6] };
    await mount([coverage()]);
    openCoverage();
    await openTab('Hechos generadores');

    const rows = [...el().querySelectorAll('.cause-rows .rule-row')].map((r) => ({
      name: text(r.querySelector('.rule-text')),
      on: r.querySelector('[role="switch"]')?.getAttribute('aria-checked'),
    }));
    expect(rows).toEqual([
      { name: 'Caída', on: 'true' },
      { name: 'Robo', on: 'false' },
    ]);
  });

  describe('with unsaved changes', () => {
    function editDescription(): void {
      const field = el().querySelector<HTMLTextAreaElement>('app-textarea textarea')!;
      field.value = 'Rotura de pantalla';
      field.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    }

    function discardModalOpen(): boolean {
      return text(el().ownerDocument.body).includes('¿Descartar los cambios sin guardar?');
    }

    it('marks the coverage as changed once the description is edited', async () => {
      await mount([coverage()]);
      openCoverage();

      editDescription();

      expect(el().querySelector('.tab-panel app-save-bar')?.textContent).toContain(
        'Cambios sin guardar',
      );
    });

    it('keeps the edit between tabs of the same coverage', async () => {
      await mount([coverage()]);
      openCoverage();
      editDescription();

      await openTab('Plazos y topes');

      expect(discardModalOpen()).toBeFalse();
      expect(activeTab()).toBe('Plazos y topes');
    });

    it('asks before leaving for the branch', async () => {
      await mount([coverage()]);
      openCoverage();
      editDescription();

      click(treeItem('Celulares'));

      expect(discardModalOpen()).toBeTrue();
      expect(levelBadge()).toBe('Cobertura');

      click(button('Descartar cambios'));

      expect(levelBadge()).toBe('Ramo');
      expect(el().querySelector('app-save-bar')?.textContent ?? '').not.toContain(
        'Cambios sin guardar',
      );
    });

    it('asks before leaving for the Fast Track tab', async () => {
      await mount([coverage()]);
      openCoverage();
      editDescription();

      await openTab('Fast Track');

      expect(discardModalOpen()).toBeTrue();

      click(button('Seguir editando'));

      expect(activeTab()).toBe('Datos');
    });
  });

  it('lets the referente rename the branch and open Marca', async () => {
    await mount([coverage()]);

    expect(el().querySelector('.rules-head app-badge')).toBeNull();
    expect(el().querySelector('[aria-label="Renombrar el ramo"]')).not.toBeNull();
    expect(sectionLabels()).toContain('Marca');
    expect(treeItem('Celulares').closest('li')?.textContent).toContain('+ Agregar cobertura');
  });

  describe('for the analyst', () => {
    beforeEach(() => {
      hardRules = [
        { ruleType: 'WAITING_PERIOD', enabled: true, deadlineHours: null },
        { ruleType: 'REPORT_DEADLINE', enabled: true, deadlineHours: null },
        { ruleType: 'MAX_EVENTS_YEAR', enabled: true, deadlineHours: null },
      ];
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
      agenda = () => of([{ documentType: 'police_report', minRiskBand: null }]);
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
      ].filter((control) => !control.disabled && !control.closest('[hidden]'));
    }

    function editActions(): string[] {
      return [...el().querySelectorAll<HTMLButtonElement>('.editor button, app-rules-tree button')]
        .filter((b) => !b.closest('[hidden]'))
        .map((b) => (b.getAttribute('aria-label') ?? b.textContent ?? '').trim())
        .filter((label) => /Agregar|Quitar|Editar|Renombrar|Guardar|Descartar/.test(label));
    }

    for (const label of [
      'Resumen',
      'Exclusiones comunes',
      'Agenda documental',
      'Derivaciones',
      'Reglas de negocio',
      'Atribuciones',
    ]) {
      it(`shows the branch tab ${label} with nothing to change or save`, async () => {
        await mount([coverage()], 'ANALISTA_SINIESTROS');
        await openTab(label);

        expect(el().querySelector('.editor app-save-bar')).toBeNull();
        expect(editableControls()).toEqual([]);
        expect(editActions()).toEqual([]);
      });
    }

    for (const label of [
      'Datos',
      'Hechos generadores',
      'Plazos y topes',
      'Liquidación',
      'Fast Track',
    ]) {
      it(`shows the coverage tab ${label} with nothing to change or save`, async () => {
        await mount([coverage()], 'ANALISTA_SINIESTROS');
        openCoverage();
        await openTab(label);

        expect(el().querySelector('.editor app-save-bar')).toBeNull();
        expect(editableControls()).toEqual([]);
        expect(editActions()).toEqual([]);
      });
    }

    it('still shows the values on screen', async () => {
      await mount([coverage()], 'ANALISTA_SINIESTROS');
      openCoverage();

      expect(el().querySelector<HTMLTextAreaElement>('app-textarea textarea')?.value).toBe(
        'Cobertura por rotura o caída accidental del equipo',
      );
    });

    it('drops the example placeholders, which read as instructions', async () => {
      await mount([coverage()], 'ANALISTA_SINIESTROS');
      openCoverage();

      const hints = [
        ...el().querySelectorAll<HTMLInputElement | HTMLTextAreaElement>(
          '.editor input, .editor textarea',
        ),
      ].map((f) => f.placeholder);
      expect(hints).not.toContain('ej. 340');
      expect(hints).not.toContain('Qué cubre, en una o dos frases');
    });

    it('lets the analyst read each coverage from the summary', async () => {
      await mount([coverage()], 'ANALISTA_SINIESTROS');

      click(iconButton('Ver la cobertura'));

      expect(title()).toBe('Daño accidental');
    });

    it('labels the screen as read-only and hides the rename pencil', async () => {
      await mount([coverage()], 'ANALISTA_SINIESTROS');

      expect(text(el().querySelector('.rules-head app-badge'))).toBe('Solo lectura');
      expect(el().querySelector('[aria-label="Renombrar el ramo"]')).toBeNull();
    });

    it('lists every insurer section except Marca', async () => {
      await mount([], 'ANALISTA_SINIESTROS');

      expect(sectionLabels()).toEqual([
        'Hard Stop',
        'Puntaje de riesgo',
        'Gestión de fraude',
        'Proveedores externos',
        'Objetivo de resolución',
        'Ciclo del expediente',
      ]);
    });

    it('shows Hard Stop with nothing to change or save', async () => {
      await mount([], 'ANALISTA_SINIESTROS');
      click(treeItem('Hard Stop'));

      expect(levelBadge()).toBe('Aseguradora');
      expect(el().querySelectorAll('.editor app-rule-block').length).toBe(2);
      expect(el().querySelector('.editor app-save-bar')).toBeNull();
      expect(editableControls()).toEqual([]);
    });
  });
});
