import { ChangeDetectionStrategy, Component, inject, signal, computed } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ExpedienteService, CaseCreateRequest } from '../expediente.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';

type Step = 1 | 2 | 3;

interface ClaimType {
  key: string;
  label: string;
  branch: string;
  product: string;
  claimCause: string;
}

interface DocSlot {
  type: string;
  label: string;
  file: File | null;
}

@Component({
  selector: 'app-nueva-denuncia',
  imports: [FormsModule, ButtonComponent, InputComponent, TextareaComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './nueva-denuncia.component.html',
  styleUrl: './nueva-denuncia.component.scss',
})
export class NuevaDenunciaComponent {
  private readonly router = inject(Router);
  private readonly service = inject(ExpedienteService);
  private readonly session = inject(InsuredSessionService);

  protected readonly step = signal<Step>(1);
  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly submittedCase = signal<ExpedienteResponse | null>(null);

  // Step 1
  protected readonly claimTypes: ClaimType[] = [
    { key: 'robo', label: 'Robo', branch: 'Celulares', product: 'Bolso – Compra Protegida', claimCause: 'Robo en vía pública' },
    { key: 'hurto', label: 'Hurto', branch: 'Celulares', product: 'Bolso – Compra Protegida', claimCause: 'Hurto' },
    { key: 'rotura', label: 'Rotura accidental', branch: 'Celulares', product: 'Combinado Familiar', claimCause: 'Rotura accidental' },
    { key: 'otro', label: 'Otro', branch: 'General', product: 'Multirriesgo', claimCause: 'Siniestro general' },
  ];
  protected readonly selectedType = signal<ClaimType | null>(null);
  protected readonly policyNumber = signal('');
  protected readonly policyTouched = signal(false);

  protected readonly mockPolicies = [
    { number: 'POL-001', label: 'POL-001 — Test Insured (Celular)' },
    { number: 'POL-CEL-2024-001', label: 'POL-CEL-2024-001 — Laura Fernández (Celular Protegido Básico)' },
    { number: 'POL-CEL-2025-099', label: 'POL-CEL-2025-099 — Marcelo Gómez (Celular Protegido Premium)' },
    { number: 'POL-CEL-2026-042', label: 'POL-CEL-2026-042 — Póliza Demo' },
  ];

  protected readonly step1Valid = computed(() => !!this.selectedType() && this.policyNumber().length > 0);

  // Step 2
  protected readonly description = signal('');
  protected readonly insuredItem = signal('');
  // Prellenado con la identidad en sesión (cuando llegue Auth0, sale del JWT).
  protected readonly insuredId = signal(this.session.insuredId() ?? '');
  protected readonly provincia = signal('');
  protected readonly localidad = signal('');
  protected readonly calleNumero = signal('');
  protected readonly entreCalles = signal('');
  protected readonly eventDate = signal('');
  protected readonly claimedAmount = signal<string>('');

  protected readonly descCount = computed(() => this.description().length);
  protected readonly step2Valid = computed(() => this.descCount() >= 50);

  // Step 3
  protected readonly docSlots = signal<DocSlot[]>([
    { type: 'police_report', label: 'Denuncia policial', file: null },
    { type: 'item_photo', label: 'Foto del bien', file: null },
    { type: 'invoice', label: 'Factura de compra', file: null },
    { type: 'quote', label: 'Presupuesto de reparación', file: null },
  ]);

  protected readonly docsCount = computed(() => this.docSlots().filter(d => d.file).length);

  selectType(t: ClaimType): void {
    this.selectedType.set(t);
  }

  blurPolicy(): void {
    this.policyTouched.set(true);
  }

  next(): void {
    if (this.step() < 3) {
      this.step.update(s => (s + 1) as Step);
    }
  }

  prev(): void {
    if (this.step() > 1) {
      this.step.update(s => (s - 1) as Step);
    }
  }

  onFileChange(index: number, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.docSlots.update(slots => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file };
      return updated;
    });
  }

  removeFile(index: number): void {
    this.docSlots.update(slots => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file: null };
      return updated;
    });
  }

  private buildEventLocation(): string {
    const parts = [this.calleNumero(), this.localidad(), this.provincia()].filter((p) => p.trim().length > 0);
    const base = parts.join(', ');
    return this.entreCalles().trim() ? `${base} (entre ${this.entreCalles()})` : base;
  }

  submit(): void {
    const type = this.selectedType();
    if (!type || this.submitting()) return;

    this.submitting.set(true);
    this.submitError.set(null);

    const request: CaseCreateRequest = {
      branch: type.branch,
      product: type.product,
      claimCause: type.claimCause,
      insuredItem: this.insuredItem(),
      insuredId: this.insuredId(),
      policyNumber: this.policyNumber(),
      description: this.description(),
      eventDate: this.eventDate() + 'T00:00:00',
      eventLocation: this.buildEventLocation(),
      claimedAmount: this.claimedAmount() ? Number(this.claimedAmount()) : undefined,
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
        // La denuncia la registra el asegurado: queda identificado con el id que usó
        // y sigue el expediente desde su portal (no desde la vista del analista).
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
