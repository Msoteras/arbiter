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
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  map,
  of,
  retry,
  scan,
  startWith,
  switchMap,
} from 'rxjs';

import { ExpedienteService, CaseCreateRequest } from '../expediente.service';
import { PolicyService } from '../policy.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { Policy } from '../../../core/models/policy';
import { ChipGroupComponent, ChipOption } from '../../../shared/ui/chip-group/chip-group.component';
import {
  addDays,
  isPoliceReportBeforeEvent,
  isTypedDate,
  todayIso,
} from '../../../core/util/datetime';
import {
  CASE_DOCUMENT_TYPES,
  CaseDocumentType,
  documentTypeLabel,
} from '../../../core/models/case-document';
import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ArgentinaLocationsService } from '../../../core/services/argentina-locations.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { FilePreviewComponent } from '../../../shared/ui/file-preview/file-preview.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { SwitchComponent } from '../../../shared/ui/switch/switch.component';

type Step = 1 | 2 | 3;

// Only determines the claim cause; branch and product come from the selected policy.
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

/**
 * - `configured`: every agenda row is mandatory.
 * - `none`: the backend answered "no documents"; the full catalog is offered, nothing demanded.
 * - `unavailable`: rules-service didn't answer. Not the same as "none": filing is still allowed and
 *   the backend re-checks completeness against the real agenda.
 */
type RequiredDocsStatus = 'loading' | 'configured' | 'none' | 'unavailable';

interface RequiredDocsState {
  status: RequiredDocsStatus;
  slots: readonly CaseDocumentType[];
  /** The slots are only the first round: more may be asked for later, from the case follow-up. */
  firstRound?: boolean;
}

/** What is shown when there is no schedule to go by: offered, never demanded. */
const OFFERED_DOCS: RequiredDocsState = { status: 'none', slots: CASE_DOCUMENT_TYPES };

/** Emitted on every combination change, so the previous slots don't linger while loading. */
const LOADING_DOCS: RequiredDocsState = { status: 'loading', slots: [] };

// Must match cases-service's spring.servlet.multipart.max-file-size. The input's `accept` is only
// a file-picker hint, so the real validation happens here.
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
  // While loading, `list` holds the previous result so the chips don't flicker.
  | { status: 'loading'; list?: ClaimType[] }
  | { status: 'ok'; list: ClaimType[] };

type EligibilityState =
  | { status: 'idle' }
  // `previous` keeps the last verdict on screen while revalidating, so the modal doesn't jump.
  | { status: 'checking'; previous?: EligibilityState }
  | { status: 'ok' }
  | { status: 'blocked'; reason: string }
  // Unknown, not 'ok'. Doesn't block "Siguiente" (submit re-checks) but the insured is told.
  | { status: 'unknown' };

const SLOT_TIMES: Record<string, string> = {
  madrugada: '03:00',
  manana: '09:00',
  tarde: '15:00',
  noche: '21:00',
};

/** Boundaries follow everyday speech (afternoon starts at noon, night at dusk), not six-hour blocks. */
function slotOf(time: string): string {
  if (!/^\d{2}:\d{2}$/.test(time)) {
    return '';
  }
  const hour = Number(time.slice(0, 2));
  if (hour < 6) return 'madrugada';
  if (hour < 12) return 'manana';
  if (hour < 19) return 'tarde';
  return 'noche';
}

@Component({
  selector: 'app-nueva-denuncia',
  imports: [
    ChipGroupComponent,
    RouterLink,
    ButtonComponent,
    CardComponent,
    InputComponent,
    TextareaComponent,
    SelectComponent,
    FilePreviewComponent,
    InlineLoadingComponent,
    SwitchComponent,
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
  private readonly locations = inject(ArgentinaLocationsService);

  /** Inside a modal: hides the header and outer box and enables `close`. */
  readonly embedded = input(false);
  /** Only meaningful when embedded. */
  readonly close = output<void>();

  protected readonly steps: Step[] = [1, 2, 3];
  protected readonly step = signal<Step>(1);
  protected readonly maxStepReached = signal<Step>(1);
  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly submittedCase = signal<ExpedienteResponse | null>(null);

  private readonly insuredId = this.session.insuredId();

  // Driven by policiesRetry because toSignal subscribes only once; without it a failed fetch
  // could not be retried without reloading the page.
  private readonly policiesRetry = signal(0);

  protected readonly policiesState = toSignal(
    toObservable(this.policiesRetry).pipe(
      switchMap(() =>
        this.insuredId
          ? this.policyService.listByInsured(this.insuredId).pipe(
              map((list): PoliciesState => ({ status: 'ok', list })),
              startWith<PoliciesState>({ status: 'loading' }),
              catchError(() => of<PoliciesState>({ status: 'error' })),
            )
          : of<PoliciesState>({ status: 'no-identity' }),
      ),
    ),
    { initialValue: { status: 'loading' } as PoliciesState },
  );

  protected retryPolicies(): void {
    this.policiesRetry.update((n) => n + 1);
  }

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

  // Portable-tech policies aren't tied to one device (unlike phones), so the insured item can't be
  // prefilled and locked from the policy.
  protected readonly lockInsuredItem = computed<boolean>(() => {
    const policy = this.selectedPolicy();
    return !!policy?.insuredItem && policy.branch !== 'Tecnología Portátil';
  });

  private readonly autofillEffect = effect(() => {
    const policy = this.selectedPolicy();
    if (policy) {
      if (policy.insuredItem && this.lockInsuredItem()) this.insuredItem.set(policy.insuredItem);
      if (policy.contactEmail) this.contactEmail.set(policy.contactEmail);
      if (policy.contactPhone) this.contactPhone.set(policy.contactPhone);
    }
  });

  // Claim causes of the policy's branch minus those its coverage excludes. Keyed on policyNumber,
  // not only branch: two policies of the same branch can exclude different causes.
  protected readonly claimTypesState = toSignal(
    toObservable(
      computed(() => {
        const policy = this.selectedPolicy();
        return policy ? { branch: policy.branch, policyNumber: policy.policyNumber } : null;
      }),
    ).pipe(
      // The computed yields a new object on every recompute; compare by value to avoid refetching.
      distinctUntilChanged(
        (a, b) => a?.branch === b?.branch && a?.policyNumber === b?.policyNumber,
      ),
      switchMap((selected) =>
        selected
          ? this.policyService.listClaimCauses(selected.branch, selected.policyNumber).pipe(
              map((names): ClaimTypesState => ({
                status: 'ok',
                list: names.map((name): ClaimType => ({
                  key: name,
                  label: name,
                  claimCause: name,
                })),
              })),
              startWith<ClaimTypesState>({ status: 'loading' }),
              catchError(() => of<ClaimTypesState>({ status: 'ok', list: [] })),
            )
          : of<ClaimTypesState>({ status: 'idle' }),
      ),
      scan(
        (prev, next): ClaimTypesState =>
          next.status === 'loading' && prev.status === 'ok'
            ? { status: 'loading', list: prev.list }
            : next,
        { status: 'idle' } as ClaimTypesState,
      ),
    ),
    { initialValue: { status: 'idle' } as ClaimTypesState },
  );

  protected readonly claimTypesLoading = computed(() => {
    const s = this.claimTypesState();
    return s.status === 'loading' && !s.list?.length;
  });

  protected readonly claimTypes = computed<ClaimType[]>(() => {
    const s = this.claimTypesState();
    return s.status === 'ok' ? s.list : s.status === 'loading' ? (s.list ?? []) : [];
  });
  protected readonly selectedType = signal<ClaimType | null>(null);
  // A branch change may invalidate the selected cause, which the backend would reject.
  private readonly resetSelectedType = effect(() => {
    const types = this.claimTypes();
    const current = untracked(() => this.selectedType());
    if (current && !types.some((t) => t.claimCause === current.claimCause)) {
      this.selectedType.set(null);
    }
  });

  protected readonly step1Valid = computed(
    () =>
      this.missingStep1().length === 0 &&
      this.eligibilityError() === null &&
      !this.eligibilityChecking(),
  );

  /** See {@link missingStep2}. */
  protected readonly missingStep1 = computed<string[]>(() => {
    const missing: string[] = [];
    if (!this.selectedPolicy()) missing.push('Póliza');
    if (!this.selectedType()) missing.push('Qué te pasó');
    return missing;
  });

  protected readonly missingFields = computed<string[]>(() =>
    this.step() === 1 ? this.missingStep1() : this.step() === 2 ? this.missingStep2() : [],
  );

  /** Only the first few names: listing all nine fields of a fresh step helps nobody. */
  protected readonly missingFieldsLabel = computed(() => {
    const missing = this.missingFields();
    const shown = missing.slice(0, 3).join(', ');
    return missing.length > 3 ? `${shown} y ${missing.length - 3} más` : shown;
  });

  // Datepicker hint only; CaseRequest enforces it again.
  protected readonly today = todayIso();

  protected readonly description = signal('');
  protected readonly insuredItem = signal('');
  // Picked from a catalog, not typed: `cases.province`/`cases.locality` are used for grouping.
  protected readonly provincia = signal('');
  protected readonly localidad = signal('');

  private readonly provinceNames = toSignal(this.locations.provinces(), {
    initialValue: [] as string[],
  });

  protected readonly provinciaOptions = computed<SelectOption[]>(() =>
    this.provinceNames().map((name) => ({ value: name, label: name })),
  );

  private readonly localityNames = toSignal(
    toObservable(this.provincia).pipe(switchMap((province) => this.locations.localities(province))),
    { initialValue: [] as string[] },
  );

  protected readonly localidadOptions = computed<SelectOption[]>(() =>
    this.localityNames().map((name) => ({ value: name, label: name })),
  );

  protected readonly localidadDisabled = computed(() => this.provincia() === '');

  /** A province change invalidates the selected locality. */
  private readonly resetLocalityEffect = effect(() => {
    this.provincia();
    untracked(() => this.localidad.set(''));
  });
  protected readonly calleNumero = signal('');
  protected readonly entreCalles = signal('');
  protected readonly eventDate = signal('');
  protected readonly eventTime = signal('');
  // Kept apart from eventDate: the gap between them is what `coverage.report_deadline_hours` evaluates.
  protected readonly policeReportDate = signal('');
  protected readonly policeReportTime = signal('');
  /**
   * Explicit: a blank date can't tell "not filed yet" from "skipped", and the deadline rule reads it.
   * Starts ON: where a police report is needed, not having it must be declared.
   */
  protected readonly policeReportFiled = signal(true);

  /** Turning it off clears the date so an undeclared report never reaches the backend. */
  setPoliceReportFiled(filed: boolean): void {
    this.policeReportFiled.set(filed);
    if (!filed) {
      this.policeReportDate.set('');
      this.policeReportTime.set('');
    }
  }
  /**
   * Each slot writes its midpoint, not its edge, so picking one doesn't push the time against a
   * deadline counted in hours. The slot is derived from the time field, which remains the value.
   */
  protected readonly timeSlots: readonly ChipOption[] = [
    { value: 'madrugada', label: 'Madrugada' },
    { value: 'manana', label: 'Mañana' },
    { value: 'tarde', label: 'Tarde' },
    { value: 'noche', label: 'Noche' },
  ];

  /** Built once so the chips don't shift if the form is filled across midnight. */
  private readonly eventDateShortcuts: Record<string, string> = {
    hoy: this.today,
    ayer: addDays(this.today, -1),
    anteayer: addDays(this.today, -2),
  };

  /** The chip writes the date field, which remains the actual value. */
  protected readonly eventDateOptions: readonly ChipOption[] = [
    { value: 'hoy', label: 'Hoy' },
    { value: 'ayer', label: 'Ayer' },
    { value: 'anteayer', label: 'Anteayer' },
  ];

  /** Derived from the field, so typing a date by hand lights up the matching chip. */
  protected readonly eventDateShortcut = computed(() => {
    const date = this.eventDate();
    return (
      Object.keys(this.eventDateShortcuts).find((k) => this.eventDateShortcuts[k] === date) ?? ''
    );
  });

  selectEventDateShortcut(key: string): void {
    this.eventDate.set(this.eventDateShortcuts[key] ?? '');
  }

  /** Relative to the event date, so no option can express a report filed before the event. */
  protected readonly policeDateOptions = computed<ChipOption[]>(() => {
    const options: ChipOption[] = [{ value: 'mismo', label: 'El mismo día' }];
    // Offering "al día siguiente" for a claim that happened today would be offering tomorrow.
    if (this.eventDate() !== this.today) {
      options.push({ value: 'siguiente', label: 'Al día siguiente' });
    }
    return options;
  });

  /** Without an event date there is nothing to anchor to, and the chips would write ''. */
  protected readonly policeDateAnchored = computed(() => isTypedDate(this.eventDate()));

  protected readonly policeDateShortcut = computed(() => {
    const police = this.policeReportDate();
    const event = this.eventDate();
    if (!isTypedDate(police) || !isTypedDate(event)) {
      return '';
    }
    if (police === event) {
      return 'mismo';
    }
    return police === addDays(event, 1) ? 'siguiente' : '';
  });

  selectPoliceDateShortcut(key: string): void {
    const event = this.eventDate();
    this.policeReportDate.set(key === 'siguiente' ? addDays(event, 1) : event);
  }

  protected readonly eventTimeSlot = computed(() => slotOf(this.eventTime()));
  protected readonly policeTimeSlot = computed(() => slotOf(this.policeReportTime()));

  setEventTimeSlot(slot: string): void {
    this.eventTime.set(SLOT_TIMES[slot] ?? '');
  }

  setPoliceTimeSlot(slot: string): void {
    this.policeReportTime.set(SLOT_TIMES[slot] ?? '');
  }

  protected readonly claimedAmount = signal<string>('');
  protected readonly contactEmail = signal('');
  protected readonly contactPhone = signal('');

  /** Local date-coherence checks; term, waiting period and arrears come from {@link backendEligibility}. */
  protected readonly dateCoherenceError = computed<string | null>(() => {
    const eventDate = this.eventDate();
    const policeDate = this.policeReportDate();
    // Skip while either date is half-typed (see isTypedDate).
    if (!isTypedDate(eventDate) || !isTypedDate(policeDate)) {
      return null;
    }
    if (policeDate > this.today) {
      return 'La fecha de la denuncia policial no puede ser futura.';
    }
    const eventTime = this.eventTime();
    const policeTime = this.policeReportTime();
    // Wait for all four fields so the insured isn't blocked mid-entry; the backend re-validates anyway.
    if (!eventTime || !policeTime) {
      return null;
    }
    if (!isPoliceReportBeforeEvent(eventDate, eventTime, policeDate, policeTime)) {
      return null;
    }
    return policeDate === eventDate
      ? `Ese día el siniestro fue a las ${eventTime}, así que la denuncia policial no pudo ser a las ${policeTime}.`
      : 'La denuncia policial no puede ser anterior al siniestro. Revisá las dos fechas.';
  });

  /** A real policy block, unlike a date-coherence error, which the insured can fix in the form. */
  protected readonly policyBlocked = computed(
    () => this.eligibilityError() !== null && this.dateCoherenceError() === null,
  );

  /**
   * Same gate `POST /cases` runs at intake. Fails OPEN on errors: submit enforces it again.
   * Fires with just a policy (arrears need no date). Once `eventDate` is set it also waits for
   * `eventTime`, since checking against midnight would give a result that flips moments later.
   */
  private readonly eligibilityCheck = toSignal(
    toObservable(
      computed(() => {
        const policy = this.selectedPolicy();
        if (!policy || this.dateCoherenceError()) {
          return null;
        }
        const eventDate = this.eventDate();
        const eventTime = this.eventTime();
        if (eventDate && !eventTime) {
          return null;
        }
        // Sent only once complete: defaulting a missing time to midnight would put a same-day
        // report before the event and trip the backend's ordering check mid-entry.
        const policeDate = this.policeReportDate();
        const policeTime = this.policeReportTime();
        return {
          insuredId: policy.insuredId,
          policyNumber: policy.policyNumber,
          eventDate: eventDate ? eventDate + 'T' + eventTime + ':00' : undefined,
          policeReportAt:
            isTypedDate(policeDate) && policeTime
              ? policeDate + 'T' + policeTime + ':00'
              : undefined,
        };
      }),
    ).pipe(
      // Compare by value: the computed yields a new object on every recompute.
      distinctUntilChanged(
        (a, b) =>
          a?.insuredId === b?.insuredId &&
          a?.policyNumber === b?.policyNumber &&
          a?.eventDate === b?.eventDate &&
          a?.policeReportAt === b?.policeReportAt,
      ),
      debounceTime(400),
      switchMap((req) =>
        req
          ? this.service.checkEligibility(req).pipe(
              map((res): EligibilityState =>
                res.eligible
                  ? { status: 'ok' }
                  : {
                      status: 'blocked',
                      reason: res.reason ?? 'No se puede registrar la denuncia.',
                    },
              ),
              startWith<EligibilityState>({ status: 'checking' }),
              catchError(() => of<EligibilityState>({ status: 'unknown' })),
            )
          : of<EligibilityState>({ status: 'idle' }),
      ),
      scan(
        (prev, next): EligibilityState =>
          next.status === 'checking' && (prev.status === 'ok' || prev.status === 'blocked')
            ? { status: 'checking', previous: prev }
            : next,
        { status: 'idle' } as EligibilityState,
      ),
    ),
    { initialValue: { status: 'idle' } as EligibilityState },
  );

  /** Keeps the previous verdict while revalidating. */
  private readonly eligibilityVerdict = computed<EligibilityState>(() => {
    const check = this.eligibilityCheck();
    return check.status === 'checking' ? (check.previous ?? check) : check;
  });

  protected readonly eligibilityChecking = computed(() => {
    const check = this.eligibilityCheck();
    return check.status === 'checking' && !check.previous;
  });

  // Fail-open, but the insured is told the policy couldn't be verified.
  protected readonly eligibilityUnknown = computed(
    () => this.eligibilityVerdict().status === 'unknown',
  );

  protected readonly eligibilityError = computed<string | null>(() => {
    const dateError = this.dateCoherenceError();
    if (dateError) {
      return dateError;
    }
    const check = this.eligibilityVerdict();
    return check.status === 'blocked' ? check.reason : null;
  });

  /** Missing step-2 fields by on-screen label, in form order; the action bar lists them. */
  protected readonly missingStep2 = computed<string[]>(() => {
    // A blocked policy hides the form; listing invisible fields would bury the real reason.
    if (this.policyBlocked()) {
      return [];
    }
    const missing: string[] = [];
    const add = (label: string, value: string) => {
      if (value.trim() === '') missing.push(label);
    };
    add('Bien asegurado', this.insuredItem());
    add('Fecha del hecho', this.eventDate());
    add('Hora del hecho', this.eventTime());
    // Same conditions under which the block is rendered.
    if (this.requiresPoliceReport() && this.policeReportFiled()) {
      add('Fecha de la denuncia policial', this.policeReportDate());
      add('Hora de la denuncia policial', this.policeReportTime());
    }
    add('Provincia', this.provincia());
    add('Localidad', this.localidad());
    // eventLocation (street only) is @NotBlank in the backend.
    add('Calle y número', this.calleNumero());
    add('Descripción del hecho', this.description());
    add('Email de contacto', this.contactEmail());
    add('Teléfono de contacto', this.contactPhone());
    return missing;
  });

  // Mirrors CaseRequest's @NotBlank/@NotNull fields so the insured isn't stopped only at submit.
  protected readonly step2Valid = computed(
    () =>
      this.missingStep2().length === 0 &&
      this.eventDate() <= this.today &&
      this.eligibilityError() === null &&
      !this.eligibilityChecking(),
  );

  protected readonly docSlots = signal<DocSlot[]>(
    CASE_DOCUMENT_TYPES.map(({ type, label }) => ({ type, label, file: null, error: null })),
  );

  // Only the Fast Track minimum is asked here; if the claim misses Fast Track, the rest is requested
  // later (AWAITING_DOCUMENTATION).
  private readonly requiredDocsState = toSignal(
    toObservable(
      computed(() => ({
        policyNumber: this.selectedPolicy()?.policyNumber ?? null,
        branch: this.selectedPolicy()?.branch ?? null,
        claimCause: this.selectedType()?.claimCause ?? null,
      })),
    ).pipe(
      // Compare by value: each emission rebuilds the slots and would drop already attached files.
      distinctUntilChanged(
        (a, b) =>
          a.policyNumber === b.policyNumber &&
          a.branch === b.branch &&
          a.claimCause === b.claimCause,
      ),
      switchMap(({ policyNumber, branch, claimCause }) =>
        policyNumber && branch && claimCause
          ? this.service.intakeDocuments(policyNumber, branch, claimCause).pipe(
              // A blip must not be read as "this claim cause needs no documents". Two retries with
              // a pause first; only a list that stays unreachable becomes 'unavailable'.
              retry({ count: 2, delay: 1000 }),
              map(({ documentTypes, fastTrackOnly }): RequiredDocsState =>
                documentTypes.length
                  ? {
                      status: 'configured',
                      slots: documentTypes.map((type) => ({
                        type,
                        label: documentTypeLabel(type),
                      })),
                      firstRound: fastTrackOnly,
                    }
                  : OFFERED_DOCS,
              ),
              catchError(() =>
                of<RequiredDocsState>({ status: 'unavailable', slots: CASE_DOCUMENT_TYPES }),
              ),
              startWith(LOADING_DOCS),
            )
          : of<RequiredDocsState>(OFFERED_DOCS),
      ),
    ),
    { initialValue: OFFERED_DOCS },
  );
  private readonly rebuildDocSlots = effect(() => {
    const { slots } = this.requiredDocsState();
    this.docSlots.set(slots.map(({ type, label }) => ({ type, label, file: null, error: null })));
  });

  protected readonly docsRequired = computed(
    () => this.requiredDocsState().status === 'configured',
  );

  protected readonly docsFirstRound = computed(() => !!this.requiredDocsState().firstRound);

  protected readonly docsUnavailable = computed(
    () => this.requiredDocsState().status === 'unavailable',
  );

  protected readonly docsCount = computed(() => this.docSlots().filter((d) => d.file).length);

  /**
   * Agenda slots without a file. Every agenda row is mandatory; the catalog fallback (no agenda or
   * rules-service down) demands nothing.
   */
  protected readonly missingDocs = computed(() =>
    this.docsRequired() ? this.docSlots().filter((slot) => !slot.file) : [],
  );

  protected readonly missingDocLabels = computed(() =>
    this.missingDocs()
      .map((slot) => slot.label)
      .join(', '),
  );

  protected readonly canSubmit = computed(
    () => !this.submitting() && this.missingDocs().length === 0,
  );

  /** No police report declared but the agenda requires it: warned in step 2 rather than at upload. */
  protected readonly policeReportMissingBlocks = computed(
    () => this.docsRequired() && this.requiresPoliceReport() && !this.policeReportFiled(),
  );

  /** Derived from the document agenda, not a hardcoded list of claim causes. */
  protected readonly requiresPoliceReport = computed(() =>
    this.requiredDocsState().slots.some(({ type }) => type === 'police_report'),
  );

  /** Switching to a cause without police report drops the previously declared one and resets the toggle. */
  private readonly resetPoliceReport = effect(() => {
    if (!this.requiresPoliceReport()) {
      untracked(() => {
        this.policeReportFiled.set(true);
        this.policeReportDate.set('');
        this.policeReportTime.set('');
      });
    }
  });

  protected readonly claimTypeOptions = computed<ChipOption[]>(() =>
    this.claimTypes().map((t) => ({ value: t.key, label: t.label })),
  );

  selectClaimType(key: string): void {
    this.selectedType.set(this.claimTypes().find((t) => t.key === key) ?? null);
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

  /** Any reached step is navigable; a new one still requires "Continuar". */
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
      updated[index] = { ...updated[index], file: error ? null : file, error };
      return updated;
    });
    // Otherwise picking the same file twice doesn't fire (change).
    input.value = '';
  }

  removeFile(index: number): void {
    this.docSlots.update((slots) => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file: null, error: null };
      return updated;
    });
  }

  /** Street address only; locality and province travel in their own fields. */
  private buildEventAddress(): string {
    const base = this.calleNumero().trim();
    return this.entreCalles().trim() ? `${base} (entre ${this.entreCalles()})` : base;
  }

  submit(): void {
    const type = this.selectedType();
    const policy = this.selectedPolicy();
    if (!type || !policy || !this.canSubmit()) return;

    this.submitting.set(true);
    this.submitError.set(null);

    const request: CaseCreateRequest = {
      branch: policy.branch,
      product: policy.product,
      claimCause: type.claimCause,
      insuredItem: this.insuredItem(),
      insuredId: policy.insuredId,
      policyNumber: policy.policyNumber,
      description: this.description(),
      eventDate: this.eventDate() + 'T' + (this.eventTime() || '00:00') + ':00',
      eventLocation: this.buildEventAddress(),
      province: this.provincia() || undefined,
      locality: this.localidad() || undefined,
      // Only when declared: an invented date would be evaluated by the deadline rule as real.
      policeReportAt: this.policeReportDate()
        ? this.policeReportDate() + 'T' + (this.policeReportTime() || '00:00') + ':00'
        : undefined,
      claimedAmount: this.claimedAmount() ? Number(this.claimedAmount()) : undefined,
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
      if (this.embedded()) {
        this.close.emit();
      }
      // insurerSlug routes to the right tenant when the insured has policies at several insurers.
      this.router.navigate(['/portal/cases', created.id], {
        queryParams: created.insurerSlug ? { insurer: created.insurerSlug } : {},
      });
    }
  }

  cancel(): void {
    this.close.emit();
  }
}
