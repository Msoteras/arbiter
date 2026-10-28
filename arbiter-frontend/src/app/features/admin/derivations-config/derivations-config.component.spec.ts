import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { DerivationsConfigComponent } from './derivations-config.component';
import {
  DerivationRulesService,
  ExpertDerivationRule,
  RepairDerivationRule,
} from '../derivation-rules.service';

describe('DerivationsConfigComponent', () => {
  let fixture: ComponentFixture<DerivationsConfigComponent>;
  let service: jasmine.SpyObj<DerivationRulesService>;

  const EXPERT: ExpertDerivationRule = { enabled: true, minClaimedAmount: 500000 };
  const REPAIR: RepairDerivationRule = { enabled: true, claimCauseIds: [1, 4] };

  async function mount(expert = of(EXPERT), repair = of(REPAIR)): Promise<void> {
    service = jasmine.createSpyObj<DerivationRulesService>('DerivationRulesService', [
      'getExpert',
      'saveExpert',
      'getRepair',
      'saveRepair',
    ]);
    service.getExpert.and.returnValue(expert);
    service.getRepair.and.returnValue(repair);
    service.saveExpert.and.callFake((_branchId, rule) => of(rule));
    service.saveRepair.and.callFake((_branchId, rule) => of(rule));

    await TestBed.configureTestingModule({
      imports: [DerivationsConfigComponent],
      providers: [provideNoopAnimations(), { provide: DerivationRulesService, useValue: service }],
    }).compileComponents();

    fixture = TestBed.createComponent(DerivationsConfigComponent);
    fixture.componentRef.setInput('branchId', 1);
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function amountField(): HTMLInputElement | null {
    return el().querySelector<HTMLInputElement>('app-input input');
  }

  function type(value: string): void {
    const field = amountField()!;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function toggleExpert(): void {
    el().querySelector<HTMLElement>('app-switch button, app-switch [role="switch"]')!.click();
    fixture.detectChanges();
  }

  function saveButton(): HTMLButtonElement {
    return [...el().querySelectorAll<HTMLButtonElement>('app-save-bar button')].find((b) =>
      b.textContent?.includes('Guardar'),
    )!;
  }

  function discardButton(): HTMLButtonElement {
    return [...el().querySelectorAll<HTMLButtonElement>('app-save-bar button')].find((b) =>
      b.textContent?.includes('Descartar'),
    )!;
  }

  it('shows the amount the branch has stored, with thousands separators', async () => {
    await mount();

    expect(service.getExpert).toHaveBeenCalledWith(1);
    expect(amountField()?.value).toBe('500.000');
    expect(saveButton().disabled).toBeTrue();
  });

  it('shows the rule to an analyst with nothing to change or save', async () => {
    await mount();
    fixture.componentRef.setInput('readOnly', true);
    fixture.detectChanges();

    expect(amountField()?.value).toBe('500.000');
    expect(amountField()?.disabled).toBeTrue();
    expect(el().querySelector<HTMLButtonElement>('[role="switch"]')?.disabled).toBeTrue();
    expect(el().querySelector('app-save-bar')).toBeNull();
  });

  it('asks for an amount before letting an enabled rule be saved', async () => {
    await mount();

    type('');

    expect(el().textContent).toContain('Indicá desde qué monto se deriva a peritaje.');
    expect(saveButton().disabled).toBeTrue();
  });

  it('saves the new amount as a number', async () => {
    await mount();

    type('600000');
    saveButton().click();
    fixture.detectChanges();

    expect(service.saveExpert).toHaveBeenCalledOnceWith(1, {
      enabled: true,
      minClaimedAmount: 600000,
    });
    expect(saveButton().disabled).toBeTrue();
  });

  /** Each rule keeps its own history, so an untouched one must not be sent. */
  it('sends only the rule that changed', async () => {
    await mount();

    type('600000');
    saveButton().click();

    expect(service.saveRepair).not.toHaveBeenCalled();
  });

  it('replaces the amount with a note while the rule is off, and brings it back unchanged', async () => {
    await mount();

    toggleExpert();

    expect(amountField()).toBeNull();
    expect(el().textContent).toContain('No se deriva');

    toggleExpert();

    expect(amountField()?.value).toBe('500.000');
    expect(saveButton().disabled).toBeTrue();
  });

  it('turns the rule off keeping the amount in the request', async () => {
    await mount();

    toggleExpert();
    saveButton().click();

    expect(service.saveExpert).toHaveBeenCalledOnceWith(1, {
      enabled: false,
      minClaimedAmount: 500000,
    });
  });

  it('goes back to the stored values on discard', async () => {
    await mount();

    type('600000');
    discardButton().click();
    fixture.detectChanges();

    expect(saveButton().disabled).toBeTrue();
    expect(service.saveExpert).not.toHaveBeenCalled();
  });

  it('shows a branch that never configured the rule as off', async () => {
    await mount(of({ enabled: false, minClaimedAmount: null }));

    expect(amountField()).toBeNull();
    expect(el().textContent).toContain('No se deriva');
  });

  it('loads the rules of the new branch when the branch changes', async () => {
    await mount();

    fixture.componentRef.setInput('branchId', 2);
    fixture.detectChanges();

    expect(service.getExpert).toHaveBeenCalledWith(2);
    expect(service.getRepair).toHaveBeenCalledWith(2);
  });

  it('keeps what was typed and shows the backend message when the save fails', async () => {
    await mount();
    service.saveExpert.and.returnValue(
      throwError(() => ({ error: { detail: 'Indicá desde qué monto reclamado se deriva.' } })),
    );

    type('600000');
    saveButton().click();
    fixture.detectChanges();

    expect(el().textContent).toContain('Indicá desde qué monto reclamado se deriva.');
    expect(amountField()?.value).toBe('600.000');
    expect(saveButton().disabled).toBeFalse();
  });

  it('offers a retry when the rules cannot be loaded', async () => {
    await mount(throwError(() => new Error('down')));

    expect(el().textContent).toContain('No se pudieron cargar las derivaciones.');

    service.getExpert.and.returnValue(of(EXPERT));
    [...el().querySelectorAll<HTMLElement>('app-button')]
      .find((b) => b.textContent?.includes('Reintentar'))!
      .click();
    fixture.detectChanges();

    expect(amountField()?.value).toBe('500.000');
  });
});
