import { ChangeDetectionStrategy, Component, effect, inject, signal, computed } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';

import { ExpedienteService, CaseCreateRequest } from '../expediente.service';
import { PolicyService } from '../policy.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { Policy } from '../../../core/models/policy';
import { CASE_DOCUMENT_TYPES } from '../../../core/models/case-document';
import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { FilePreviewComponent } from '../../../shared/ui/file-preview/file-preview.component';
import { CheckboxComponent } from '../../../shared/ui/checkbox/checkbox.component';

// Wizard de alta de denuncia (asegurado) — 3 pasos con catálogos en cascada.
type Step = 1 | 2 | 3;

// El tipo de hecho solo determina la causa (hecho generador). El ramo y el producto
// salen de la póliza elegida, no del tipo — así lo hace la aseguradora.
interface ClaimType {
  key: string;
  label: string;
  claimCause: string;
}

interface DocSlot {
  type: string;
  label: string;
  file: File | null;
}

type PoliciesState =
  | { status: 'loading' }
  | { status: 'no-identity' }
  | { status: 'ok'; list: Policy[] }
  | { status: 'error' };

@Component({
  selector: 'app-nueva-denuncia',
  imports: [
    RouterLink,
    ButtonComponent,
    CardComponent,
    InputComponent,
    TextareaComponent,
    SelectComponent,
    CheckboxComponent,
    FilePreviewComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './nueva-denuncia.component.html',
  styleUrl: './nueva-denuncia.component.scss',
})
export class NuevaDenunciaComponent {
  private readonly router = inject(Router);
  private readonly service = inject(ExpedienteService);
  private readonly policyService = inject(PolicyService);
  private readonly session = inject(InsuredSessionService);

  protected readonly steps: Step[] = [1, 2, 3];
  protected readonly step = signal<Step>(1);
  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly submittedCase = signal<ExpedienteResponse | null>(null);

  // La identidad sale de la sesión: el asegurado ya está logueado/identificado, no
  // vuelve a tipear el DNI. (Cuando se integre Auth0, sale del JWT.)
  private readonly insuredId = this.session.insuredId();

  // Step 1 — pólizas del asegurado (de todas las aseguradoras) para elegir.
  protected readonly policiesState = toSignal(
    this.insuredId
      ? this.policyService.listByInsured(this.insuredId).pipe(
          map((list): PoliciesState => ({ status: 'ok', list })),
          startWith<PoliciesState>({ status: 'loading' }),
          catchError(() => of<PoliciesState>({ status: 'error' })),
        )
      : of<PoliciesState>({ status: 'no-identity' }),
    { initialValue: { status: 'loading' } as PoliciesState },
  );

  protected readonly policies = computed<Policy[]>(() => {
    const s = this.policiesState();
    return s.status === 'ok' ? s.list : [];
  });

  protected readonly policyOptions = computed<SelectOption[]>(() =>
    this.policies().map((p) => ({
      value: p.policyNumber,
      label: `${p.insurerName} · ${p.product} · ${p.policyNumber}`,
    })),
  );

  protected readonly selectedPolicyNumber = signal('');
  protected readonly selectedPolicy = computed<Policy | null>(
    () => this.policies().find((p) => p.policyNumber === this.selectedPolicyNumber()) ?? null,
  );

  private readonly autofillEffect = effect(() => {
    const policy = this.selectedPolicy();
    if (policy) {
      if (policy.insuredItem) this.insuredItem.set(policy.insuredItem);
      if (policy.contactEmail) this.contactEmail.set(policy.contactEmail);
      if (policy.contactPhone) this.contactPhone.set(policy.contactPhone);
    }
  });

  protected readonly claimTypes: ClaimType[] = [
    { key: 'robo', label: 'Robo', claimCause: 'Robo en vía pública' },
    { key: 'hurto', label: 'Hurto', claimCause: 'Hurto' },
    { key: 'rotura', label: 'Rotura accidental', claimCause: 'Rotura accidental' },
    { key: 'otro', label: 'Otro', claimCause: 'Siniestro general' },
  ];
  protected readonly selectedType = signal<ClaimType | null>(null);

  protected readonly step1Valid = computed(() => !!this.selectedPolicy() && !!this.selectedType());

  // Step 2
  protected readonly description = signal('');
  protected readonly insuredItem = signal('');
  protected readonly provincia = signal('');
  protected readonly localidad = signal('');
  protected readonly calleNumero = signal('');
  protected readonly entreCalles = signal('');
  protected readonly eventDate = signal('');
  protected readonly eventTime = signal('');
  protected readonly claimedAmount = signal<string>('');
  protected readonly pep = signal(false);
  protected readonly contactEmail = signal('');
  protected readonly contactPhone = signal('');

  protected readonly step2Valid = computed(() => this.description().trim().length > 0);

  // Step 3
  protected readonly docSlots = signal<DocSlot[]>(
    CASE_DOCUMENT_TYPES.map(({ type, label }) => ({ type, label, file: null })),
  );

  protected readonly docsCount = computed(() => this.docSlots().filter((d) => d.file).length);

  // Consentimiento para enviar las imágenes a un proveedor externo de verificación
  // antifraude (H0009 / docs/frontend-analisis-forense.md). A diferencia de PEP, este
  // consentimiento tiene que ser LIBRE (Ley 25.326, transferencia internacional de datos):
  // negarse NO puede impedir la denuncia — por eso no gatea "Enviar denuncia".
  protected readonly imageConsent = signal(false);

  selectType(t: ClaimType): void {
    this.selectedType.set(t);
  }

  next(): void {
    if (this.step() < 3) {
      this.step.update((s) => (s + 1) as Step);
    }
  }

  prev(): void {
    if (this.step() > 1) {
      this.step.update((s) => (s - 1) as Step);
    }
  }

  /** Volver a un paso ya completado tocando su número. Avanzar sigue gateado por "Continuar". */
  goToStep(s: Step): void {
    if (s < this.step()) {
      this.step.set(s);
    }
  }

  onFileChange(index: number, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.docSlots.update((slots) => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file };
      return updated;
    });
  }

  removeFile(index: number): void {
    this.docSlots.update((slots) => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file: null };
      return updated;
    });
  }

  private buildEventLocation(): string {
    const parts = [this.calleNumero(), this.localidad(), this.provincia()].filter(
      (p) => p.trim().length > 0,
    );
    const base = parts.join(', ');
    return this.entreCalles().trim() ? `${base} (entre ${this.entreCalles()})` : base;
  }

  submit(): void {
    const type = this.selectedType();
    const policy = this.selectedPolicy();
    if (!type || !policy || this.submitting()) return;

    this.submitting.set(true);
    this.submitError.set(null);

    const request: CaseCreateRequest = {
      // El ramo y el producto salen de la póliza elegida, no del tipo de hecho.
      branch: policy.branch,
      product: policy.product,
      claimCause: type.claimCause,
      insuredItem: this.insuredItem(),
      insuredId: policy.insuredId,
      policyNumber: policy.policyNumber,
      description: this.description(),
      eventDate: this.eventDate() + 'T' + (this.eventTime() || '00:00') + ':00',
      eventLocation: this.buildEventLocation(),
      claimedAmount: this.claimedAmount() ? Number(this.claimedAmount()) : undefined,
      pep: this.pep(),
      imageConsent: this.imageConsent(),
      contactEmail: this.contactEmail() || undefined,
      contactPhone: this.contactPhone() || undefined,
    };

    const docs = new Map<string, File>();
    for (const slot of this.docSlots()) {
      if (slot.file) {
        docs.set(slot.type, slot.file);
      }
    }

    this.service.create(request, docs.size > 0 ? docs : undefined).subscribe({
      next: (res) => {
        this.submitting.set(false);
        this.session.identify(request.insuredId);
        this.submittedCase.set(res);
      },
      error: (err) => {
        this.submitting.set(false);
        this.submitError.set(err.error?.detail || 'Error al crear el caso');
      },
    });
  }

  goToCase(): void {
    const created = this.submittedCase();
    if (created) {
      this.router.navigate(['/portal/cases', created.id]);
    }
  }
}
