import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  output,
  signal,
  computed,
  untracked,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { catchError, debounceTime, map, of, startWith, switchMap } from 'rxjs';

import { ExpedienteService, CaseCreateRequest } from '../expediente.service';
import { PolicyService } from '../policy.service';
import { DocumentAgendaService } from '../document-agenda.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { Policy } from '../../../core/models/policy';
import { CASE_DOCUMENT_TYPES, CaseDocumentType } from '../../../core/models/case-document';
import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { FilePreviewComponent } from '../../../shared/ui/file-preview/file-preview.component';
import { CheckboxComponent } from '../../../shared/ui/checkbox/checkbox.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';

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
  error: string | null;
}

// Mismo tope que cases-service (spring.servlet.multipart.max-file-size) — validar acá
// evita esperar la subida completa para recién ahí enterarse de que no entra. El
// accept="image/*,.pdf" del input es solo una sugerencia del explorador de archivos
// (se salta eligiendo "todos los archivos"), así que la validación real va acá.
const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

function fileTypeError(file: File): string | null {
  if (!file.type.startsWith('image/') && file.type !== 'application/pdf') {
    return 'Solo se aceptan imágenes o PDF.';
  }
  if (file.size > MAX_FILE_SIZE_BYTES) {
    return 'El archivo pesa más de 10 MB.';
  }
  return null;
}

type PoliciesState =
  | { status: 'loading' }
  | { status: 'no-identity' }
  | { status: 'ok'; list: Policy[] }
  | { status: 'error' };

type ClaimTypesState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ok'; list: ClaimType[] };

type EligibilityState =
  | { status: 'idle' }
  | { status: 'checking' }
  | { status: 'ok' }
  | { status: 'blocked'; reason: string };

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
    InlineLoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './nueva-denuncia.component.html',
  styleUrl: './nueva-denuncia.component.scss',
})
export class NuevaDenunciaComponent {
  private readonly router = inject(Router);
  private readonly service = inject(ExpedienteService);
  private readonly policyService = inject(PolicyService);
  private readonly agenda = inject(DocumentAgendaService);
  private readonly session = inject(InsuredSessionService);

  /**
   * `true` cuando el wizard se muestra dentro de un modal (pop-up sobre el portal) en vez de como
   * página propia: oculta el encabezado y la caja externa (el modal ya los da) y habilita `close`.
   */
  readonly embedded = input(false);
  /** Pedido de cerrar el pop-up (cancelar o después de crear). Solo tiene efecto en modo embedded. */
  readonly close = output<void>();

  protected readonly steps: Step[] = [1, 2, 3];
  protected readonly step = signal<Step>(1);
  // El paso más lejano ya alcanzado — permite ir y volver libremente dentro de lo ya
  // completado sin reabrir la validación de "Continuar" cada vez (docs/frontend-bugs-ux.md #21).
  protected readonly maxStepReached = signal<Step>(1);
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

  // Hechos generadores REALES del ramo de la póliza elegida, ya recortados por lo que la cobertura
  // de esa póliza excluye (COVERAGE_EXCLUSION) — antes eran una lista fija que no coincidía con el
  // catálogo por ramo (ej. "Rotura accidental" no existe en Tecnología, "Siniestro general" en
  // ninguno) → el backend tiraba 422 al crear el caso, y después mostraba TODOS los del ramo sin
  // mirar la cobertura → el motor recién detectaba la exclusión en la clasificación, con la
  // documentación ya subida. Vacío hasta elegir póliza. Reacciona a policyNumber, no solo a branch:
  // dos pólizas del mismo ramo pueden tener coberturas con exclusiones distintas.
  // Estado explícito (en vez de solo el array) para poder mostrar un loader mientras el fetch está
  // en vuelo: sin esto, al elegir póliza los chips de "¿Qué te pasó?" quedaban vacíos un instante,
  // como si el ramo no tuviera hechos generadores en vez de estar cargándolos.
  protected readonly claimTypesState = toSignal(
    toObservable(
      computed(() => {
        const policy = this.selectedPolicy();
        return policy ? { branch: policy.branch, policyNumber: policy.policyNumber } : null;
      }),
    ).pipe(
      switchMap((selected) =>
        selected
          ? this.policyService.listClaimCauses(selected.branch, selected.policyNumber).pipe(
              map(
                (names): ClaimTypesState => ({
                  status: 'ok',
                  list: names.map((name): ClaimType => ({ key: name, label: name, claimCause: name })),
                }),
              ),
              startWith<ClaimTypesState>({ status: 'loading' }),
              catchError(() => of<ClaimTypesState>({ status: 'ok', list: [] })),
            )
          : of<ClaimTypesState>({ status: 'idle' }),
      ),
    ),
    { initialValue: { status: 'idle' } as ClaimTypesState },
  );

  protected readonly claimTypesLoading = computed(() => this.claimTypesState().status === 'loading');

  protected readonly claimTypes = computed<ClaimType[]>(() => {
    const s = this.claimTypesState();
    return s.status === 'ok' ? s.list : [];
  });
  protected readonly selectedType = signal<ClaimType | null>(null);
  // Al cambiar de ramo la causa elegida puede dejar de existir: se limpia para no mandar un hecho
  // generador que el backend rechazaría.
  private readonly resetSelectedType = effect(() => {
    const types = this.claimTypes();
    const current = untracked(() => this.selectedType());
    if (current && !types.some((t) => t.claimCause === current.claimCause)) {
      this.selectedType.set(null);
    }
  });

  protected readonly step1Valid = computed(() => !!this.selectedPolicy() && !!this.selectedType());

  // Tope del input de fecha (docs/frontend-bugs-ux.md #16): un siniestro no puede haber
  // "ocurrido" en el futuro. La regla real vive en el backend (CaseRequest la valida de
  // nuevo); esto es solo la ayuda visual del datepicker.
  protected readonly today = new Date().toISOString().slice(0, 10);

  // Step 2
  protected readonly description = signal('');
  protected readonly insuredItem = signal('');
  protected readonly provincia = signal('');
  protected readonly localidad = signal('');
  protected readonly calleNumero = signal('');
  protected readonly entreCalles = signal('');
  protected readonly eventDate = signal('');
  protected readonly eventTime = signal('');
  // Cuándo hizo la denuncia policial, declarado por el asegurado. Separado de eventDate porque son
  // dos momentos distintos y la diferencia entre ambos es lo que evalúa la regla del plazo de
  // denuncia (`coverage.report_deadline_hours`). El dato existía en CaseRequest y en la entidad
  // desde el principio; el wizard nunca lo mandaba, así que la regla era inverificable (D12).
  protected readonly policeReportDate = signal('');
  protected readonly policeReportTime = signal('');
  protected readonly claimedAmount = signal<string>('');
  protected readonly contactEmail = signal('');
  protected readonly contactPhone = signal('');

  /**
   * The two date-coherence checks that need no backend round trip — instant, no debounce. Vigencia,
   * carencia y mora salen de {@link backendEligibility}: son las que dependen de datos que el
   * portal no trae de entrada (carencia) o de configuración del referente (mora, `onArrears`).
   */
  protected readonly dateCoherenceError = computed<string | null>(() => {
    const eventDate = this.eventDate();
    const policeReport = this.policeReportDate();
    if (!eventDate || !policeReport) {
      return null;
    }
    if (policeReport < eventDate) {
      return 'La denuncia policial no puede ser anterior al siniestro. Revisá las dos fechas.';
    }
    if (policeReport > this.today) {
      return 'La fecha de la denuncia policial no puede ser futura.';
    }
    return null;
  });

  /**
   * Same gate `POST /cases` runs at intake (vigencia, carencia, mora — `PolicyEligibilityValidator`
   * via `POST /cases/eligibility`), so the insured finds out here instead of after filling out the
   * rest of the form and uploading documentation. Debounced: fires as the policy/dates settle, not
   * on every keystroke. Fails OPEN on a network error — the real gate at submit still enforces
   * vigencia/carencia (no external call needed for those) and rules-service being down already
   * fails open server-side for mora, so blocking the wizard over a transient check failure would
   * be strictly worse than the status quo.
   */
  private readonly eligibilityCheck = toSignal(
    toObservable(
      computed(() => {
        const policy = this.selectedPolicy();
        const eventDate = this.eventDate();
        if (!policy || !eventDate || this.dateCoherenceError()) {
          return null;
        }
        return {
          insuredId: policy.insuredId,
          policyNumber: policy.policyNumber,
          eventDate: eventDate + 'T' + (this.eventTime() || '00:00') + ':00',
          policeReportAt: this.policeReportDate()
            ? this.policeReportDate() + 'T' + (this.policeReportTime() || '00:00') + ':00'
            : undefined,
        };
      }),
    ).pipe(
      debounceTime(400),
      switchMap((req) =>
        req
          ? this.service.checkEligibility(req).pipe(
              map((res): EligibilityState =>
                res.eligible
                  ? { status: 'ok' }
                  : { status: 'blocked', reason: res.reason ?? 'No se puede registrar la denuncia.' },
              ),
              startWith<EligibilityState>({ status: 'checking' }),
              catchError(() => of<EligibilityState>({ status: 'ok' })),
            )
          : of<EligibilityState>({ status: 'idle' }),
      ),
    ),
    { initialValue: { status: 'idle' } as EligibilityState },
  );

  protected readonly eligibilityChecking = computed(() => this.eligibilityCheck().status === 'checking');

  protected readonly eligibilityError = computed<string | null>(() => {
    const dateError = this.dateCoherenceError();
    if (dateError) {
      return dateError;
    }
    const check = this.eligibilityCheck();
    return check.status === 'blocked' ? check.reason : null;
  });

  // El backend exige además insuredItem, eventDate y eventLocation (@NotBlank/@NotNull en
  // CaseRequest) — sin esto el asegurado llegaba al paso 3, adjuntaba documentación, y recién
  // ahí el submit fallaba con un error genérico.
  protected readonly step2Valid = computed(
    () =>
      this.description().trim().length > 0 &&
      this.insuredItem().trim().length > 0 &&
      this.eventDate().trim().length > 0 &&
      this.eventDate() <= this.today &&
      this.eligibilityError() === null &&
      !this.eligibilityChecking() &&
      this.buildEventLocation().trim().length > 0,
  );

  // Step 3
  protected readonly docSlots = signal<DocSlot[]>(
    CASE_DOCUMENT_TYPES.map(({ type, label }) => ({ type, label, file: null, error: null })),
  );

  // El asegurado sube exactamente lo que el referente configuró como requerido para el ramo de la
  // póliza elegida (o el catálogo completo si ese ramo no tiene agenda). Al cambiar de póliza, se
  // rearman los slots según la agenda de su ramo.
  private readonly requiredDocTypes = toSignal(
    toObservable(computed(() => this.selectedPolicy()?.branch ?? null)).pipe(
      switchMap((branch) =>
        branch ? this.agenda.slotsForBranch(branch) : of(CASE_DOCUMENT_TYPES),
      ),
    ),
    { initialValue: CASE_DOCUMENT_TYPES as readonly CaseDocumentType[] },
  );
  private readonly rebuildDocSlots = effect(() => {
    const types = this.requiredDocTypes();
    this.docSlots.set(types.map(({ type, label }) => ({ type, label, file: null, error: null })));
  });

  protected readonly docsCount = computed(() => this.docSlots().filter((d) => d.file).length);

  /**
   * Si el ramo pide constancia de denuncia policial, entonces el hecho generador la lleva y tiene
   * sentido preguntar cuándo se hizo. Se deriva de la agenda documental del referente —la misma
   * fuente que arma los slots de adjuntos— en vez de una lista propia de hechos generadores: así
   * el día que el referente saque `police_report` de un ramo, el campo desaparece solo.
   */
  protected readonly requiresPoliceReport = computed(() =>
    this.requiredDocTypes().some(({ type }) => type === 'police_report'),
  );

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
      const nextStep = (this.step() + 1) as Step;
      this.step.set(nextStep);
      if (nextStep > this.maxStepReached()) {
        this.maxStepReached.set(nextStep);
      }
    }
  }

  prev(): void {
    if (this.step() > 1) {
      this.step.update((s) => (s - 1) as Step);
    }
  }

  /** Cualquier paso ya alcanzado es navegable en las dos direcciones. Uno nuevo sigue
   * gateado por "Continuar" (la validación del paso actual). */
  goToStep(s: Step): void {
    if (s <= this.maxStepReached()) {
      this.step.set(s);
    }
  }

  onFileChange(index: number, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    const error = file ? fileTypeError(file) : null;
    this.docSlots.update((slots) => {
      const updated = [...slots];
      // Un archivo inválido no se guarda: el slot queda vacío con el motivo al lado,
      // en vez de dejar avanzar y fallar recién en el submit.
      updated[index] = { ...updated[index], file: error ? null : file, error };
      return updated;
    });
    // Limpia el input nativo: sin esto, elegir el mismo archivo inválido dos veces
    // seguidas no dispara (change) la segunda vez.
    input.value = '';
  }

  removeFile(index: number): void {
    this.docSlots.update((slots) => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file: null, error: null };
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
      // Solo si se declaró: la columna es nullable y "no hubo denuncia policial" es un caso
      // legítimo, distinto de "hubo pero no sé cuándo". Mandar una fecha inventada sería peor
      // que no mandar nada, porque la regla del plazo la evaluaría como si fuera real.
      policeReportAt: this.policeReportDate()
        ? this.policeReportDate() + 'T' + (this.policeReportTime() || '00:00') + ':00'
        : undefined,
      claimedAmount: this.claimedAmount() ? Number(this.claimedAmount()) : undefined,
      // PEP ya no se declara en la denuncia: es un dato del asegurado que viene de la póliza/KYC,
      // no algo que se pregunte acá. Se manda false solo para satisfacer el contrato actual —
      // el backend debe resolver la condición PEP desde el registro del asegurado, no del request.
      pep: false,
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
      // Si está embebido, cerrar el modal antes de navegar al seguimiento del caso recién creado.
      if (this.embedded()) {
        this.close.emit();
      }
      this.router.navigate(['/portal/cases', created.id]);
    }
  }

  /** Cancelar desde el pop-up (solo embedded): cierra sin crear nada. */
  cancel(): void {
    this.close.emit();
  }
}
