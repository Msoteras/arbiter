import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { ProvidersConfigComponent } from './providers-config.component';
import { BranchesService } from '../branches.service';
import { ProviderAdmin, ProvidersService } from '../providers.service';

describe('ProvidersConfigComponent', () => {
  let fixture: ComponentFixture<ProvidersConfigComponent>;
  let providersService: jasmine.SpyObj<ProvidersService>;

  const BRANCHES = [
    { id: 1, name: 'Celulares' },
    { id: 2, name: 'Tecnología Portátil' },
  ];

  const provider = (over: Partial<ProviderAdmin> = {}): ProviderAdmin => ({
    id: 7,
    name: 'Estudio Verifica',
    email: 'perito@verifica.com',
    zone: null,
    branches: [],
    active: true,
    providerType: 'ESTUDIO_LIQUIDADOR',
    ...over,
  });

  async function mount(providers: ProviderAdmin[] = []): Promise<void> {
    providersService = jasmine.createSpyObj<ProvidersService>('ProvidersService', [
      'list',
      'create',
      'update',
      'remove',
    ]);
    providersService.list.and.returnValue(of(providers));
    providersService.create.and.returnValue(of(provider()));
    providersService.update.and.returnValue(of(provider()));
    providersService.remove.and.returnValue(of(undefined));

    await TestBed.configureTestingModule({
      imports: [ProvidersConfigComponent],
      providers: [
        provideNoopAnimations(),
        { provide: ProvidersService, useValue: providersService },
        { provide: BranchesService, useValue: { list: () => of(BRANCHES) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProvidersConfigComponent);
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function click(target: HTMLElement | null | undefined): void {
    target!.click();
    fixture.detectChanges();
  }

  function openNew(): void {
    click(
      [...el().querySelectorAll<HTMLElement>('app-button')].find((b) =>
        b.textContent?.includes('Agregar proveedor'),
      ),
    );
  }

  function openEdit(): void {
    click(el().querySelector<HTMLElement>('.provider-actions app-icon-button'));
  }

  function dialog(): HTMLElement | null {
    return el().querySelector<HTMLElement>('[role="dialog"]');
  }

  function branchBoxes(): HTMLInputElement[] {
    return [
      ...dialog()!.querySelectorAll<HTMLInputElement>('.branch-picker input[type="checkbox"]'),
    ];
  }

  function toggle(box: HTMLInputElement): void {
    box.click();
    fixture.detectChanges();
  }

  function fill(index: number, value: string): void {
    const field = dialog()!.querySelectorAll<HTMLInputElement>('.provider-form app-input input')[
      index
    ];
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function saveButton(): HTMLButtonElement {
    return [...dialog()!.querySelectorAll<HTMLButtonElement>('.modal-actions button')].find((b) =>
      b.textContent?.includes('Guardar'),
    )!;
  }

  it('lists each provider with its own edit and delete actions', async () => {
    await mount([provider()]);

    const labels = [...el().querySelectorAll('.provider-actions button')].map((b) =>
      b.getAttribute('aria-label'),
    );

    expect(labels).toEqual(['Editar Estudio Verifica', 'Borrar Estudio Verifica']);
  });

  it('opens a new provider in a side panel with every branch checked', async () => {
    await mount();

    openNew();

    expect(dialog()?.classList).toContain('side');
    expect(branchBoxes().map((b) => b.checked)).toEqual([true, true]);
  });

  it('does not let a provider be saved with no branch checked', async () => {
    await mount();
    openNew();
    fill(0, 'Peritajes SA');
    fill(1, 'contacto@peritajes.com');

    branchBoxes().forEach(toggle);

    expect(dialog()?.textContent).toContain('Elegí al menos un ramo.');
    expect(saveButton().disabled).toBeTrue();
  });

  /** A branch added later must not be covered until someone checks it: the list is always explicit. */
  it('saves every checked branch as an explicit list', async () => {
    await mount();
    openNew();
    fill(0, 'Peritajes SA');
    fill(1, 'contacto@peritajes.com');

    click(saveButton());

    expect(providersService.create).toHaveBeenCalledOnceWith(
      jasmine.objectContaining({ name: 'Peritajes SA', branchIds: [1, 2] }),
    );
  });

  it('saves only the branches left checked', async () => {
    await mount();
    openNew();
    fill(0, 'Peritajes SA');
    fill(1, 'contacto@peritajes.com');

    toggle(branchBoxes()[0]);
    click(saveButton());

    expect(providersService.create).toHaveBeenCalledOnceWith(
      jasmine.objectContaining({ branchIds: [2] }),
    );
  });

  /** Stored with no branches means it covers all of them, and that is how it has to read. */
  it('opens a provider stored without branches with all of them checked', async () => {
    await mount([provider({ branches: [] })]);

    openEdit();

    expect(branchBoxes().map((b) => b.checked)).toEqual([true, true]);
  });

  it('opens a provider of one branch with only that one checked', async () => {
    await mount([provider({ branches: [{ id: 2, name: 'Tecnología Portátil' }] })]);

    openEdit();

    expect(branchBoxes().map((b) => b.checked)).toEqual([false, true]);
  });

  it('closes the panel without saving on cancel', async () => {
    await mount();
    openNew();

    click(
      [...dialog()!.querySelectorAll<HTMLElement>('.modal-actions app-button')].find((b) =>
        b.textContent?.includes('Cancelar'),
      ),
    );

    expect(dialog()).toBeNull();
    expect(providersService.create).not.toHaveBeenCalled();
  });

  it('asks before deleting and deletes only once confirmed', async () => {
    await mount([provider()]);

    click(el().querySelectorAll<HTMLElement>('.provider-actions app-icon-button')[1]);

    expect(dialog()?.textContent).toContain('Vas a borrar a Estudio Verifica');
    expect(providersService.remove).not.toHaveBeenCalled();

    click(
      [...dialog()!.querySelectorAll<HTMLElement>('.modal-actions app-button')].find((b) =>
        b.textContent?.includes('Sí, borrar proveedor'),
      ),
    );

    expect(providersService.remove).toHaveBeenCalledOnceWith(7);
  });
});
