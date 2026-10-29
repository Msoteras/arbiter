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

import { CaseService, CaseCreateRequest } from '../case.service';
import { PolicyService } from '../policy.service';
import { CaseResponse } from '../../../core/models/case';
import { Policy } from '../../../core/models/policy';
import { ChipGroupComponent, ChipOption } from '../../../shared/ui/chip-group/chip-group.component';
import {
  addDays,
  isPoliceReportBeforeEvent,
  isTypedDate,
  todayIso,
} from '../../../core/util/datetime';
import { amountInputDisplay, amountInputLabel, amountInputValue } from '../../../core/util/money';
import { CaseDocumentType } from '../../../core/models/case-document';
import { DocumentAgendaService } from '../document-agenda.service';
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
import {
  DatePickerComponent,
  DateShortcut,
} from '../../../shared/ui/date-picker/date-picker.component';
import {
  TimePickerComponent,
  TimeSlot,
} from '../../../shared/ui/date-picker/time-picker.component';

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

/** What is shown when there is no schedule to go by: the catalog, offered, never demanded. */
const offeredDocs = (catalog: readonly CaseDocumentType[]): RequiredDocsState => ({
  status: 'none',
  slots: catalog,
});

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

@Component({
  selector: 'app-new-claim',
  imports: [
    ChipGroupComponent,
    DatePickerComponent,
    RouterLink,
    ButtonComponent,
    CardComponent,
    InputComponent,
    TextareaComponent,
    SelectComponent,
    FilePreviewComponent,
    InlineLoadingComponent,
    SwitchComponent,
    TimePickerComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './new-claim.component.html',
  styleUrl: './new-claim.component.scss',
})
export class NewClaimComponent {
  private readonly router = inject(Router);
  private readonly service = inject(CaseService);
  private readonly policyService = inject(PolicyService);
  private readonly session = inject(InsuredSessionService);
  private readonly locations = inject(ArgentinaLocationsService);
  private readonly agenda = inject(DocumentAgendaService);

  /** Inside a modal: hides the header and outer box and enables `close`. */
  readonly embedded = input(false);
  /** Only meaningful when embedded. */
  readonly close = output<void>();

  protected readonly steps: Step[] = [1, 2, 3];
  protected readonly step = signal<Step>(1);
  protected readonly maxStepReached = signal<Step>(1);
  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly submittedCase = signal<CaseResponse | null>(null);
  /** The missing fields and documents are listed only after a try to move on, not while filling in. */
  protected readonly showMissing = signal(false);
  protected readonly showMissingDocs = signal(false);

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
      // The item tells apart policies of the same product (one per phone); not every policy names one.
      label: `${p.insurerName} · ${p.insuredItem ?? p.product} · ${p.policyNumber}`,
    })),
  );

  protected readonly selectedPolicyNumber = signal('');
  protected readonly selectedPolicy = computed<Policy | null>(
    () => this.policies().find((p) => p.policyNumber === this.selectedPolicyNumber()) ?? null,
  );

  // The insured item is optional on a policy. When the policy names one, that is what's covered and
  // the insured can't declare another; when it doesn't, they say what the claim is about.
  protected readonly lockInsuredItem = computed<boolean>(
    () => !!this.selectedPolicy()?.insuredItem,
  );

  private readonly autofillEffect = effect(() => {
    const policy = this.selectedPolicy();
    if (policy) {
      // Reset on every policy change, so another policy's item never lingers in an editable field.
      this.insuredItem.set(policy.insuredItem ?? '');
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
  protected readonly selectedProvince = signal('');
  protected readonly selectedLocality = signal('');

  private readonly provinceNames = toSignal(this.locations.provinces(), {
    initialValue: [] as string[],
  });

  protected readonly provinceOptions = computed<SelectOption[]>(() =>
    this.provinceNames().map((name) => ({ value: name, label: name })),
  );

  private readonly localityNames = toSignal(
    toObservable(this.selectedProvince).pipe(
      switchMap((province) => this.locations.localities(province)),
    ),
    { initialValue: [] as string[] },
  );

  protected readonly localityOptions = computed<SelectOption[]>(() =>
    this.localityNames().map((name) => ({ value: name, label: name })),
  );

  protected readonly localityDisabled = computed(() => this.selectedProvince() === '');

  /** A province change invalidates the selected locality. */
  private readonly resetLocalityEffect = effect(() => {
    this.selectedProvince();
    untracked(() => this.selectedLocality.set(''));
  });
  protected readonly streetAddress = signal('');
  protected readonly crossStreets = signal('');
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
   * deadline counted in hours. Boundaries follow everyday speech (afternoon starts at noon, night at
   * dusk), not six-hour blocks.
   */
  protected readonly timeSlots: readonly TimeSlot[] = [
    { label: 'Madrugada', time: '03:00', startsAt: '00:00' },
    { label: 'Mañana', time: '09:00', startsAt: '06:00' },
    { label: 'Tarde', time: '15:00', startsAt: '12:00' },
    { label: 'Noche', time: '21:00', startsAt: '19:00' },
  ];

  /** Built once so the shortcuts don't shift if the form is filled across midnight. */
  protected readonly eventDateShortcuts: readonly DateShortcut[] = [
    { label: 'Hoy', date: this.today },
    { label: 'Ayer', date: addDays(this.today, -1) },
    { label: 'Anteayer', date: addDays(this.today, -2) },
  ];

  /**
   * Relative to the event date, so no option can express a report filed before the event. The
   * picker drops "Al día siguiente" when that would be tomorrow.
   */
  protected readonly policeDateShortcuts = computed<DateShortcut[]>(() => {
    const event = this.eventDate();
    if (!isTypedDate(event)) {
      return [];
    }
    return [
      { label: 'El mismo día', date: event },
      { label: 'Al día siguiente', date: addDays(event, 1) },
    ];
  });

  protected readonly claimedAmount = signal<string>('');
  protected readonly amountInputLabel = amountInputLabel;
  protected readonly amountInputValue = amountInputValue;
  protected readonly amountInputDisplay = amountInputDisplay;
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
    add('Provincia', this.selectedProvince());
    add('Localidad', this.selectedLocality());
    // eventLocation (street only) is @NotBlank in the backend.
    add('Calle y número', this.streetAddress());
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

  protected readonly docSlots = signal<DocSlot[]>([]);

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
              switchMap(({ documentTypes, fastTrackOnly }) =>
                this.agenda.catalogSlots().pipe(
                  map((catalog): RequiredDocsState =>
                    documentTypes.length
                      ? {
                          status: 'configured',
                          slots: documentTypes.map((type) => ({
                            type,
                            label: catalog.find((slot) => slot.type === type)?.label ?? type,
                          })),
                          firstRound: fastTrackOnly,
                        }
                      : offeredDocs(catalog),
                  ),
                ),
              ),
              catchError(() =>
                this.agenda.catalogSlots().pipe(
                  map((catalog): RequiredDocsState => ({
                    status: 'unavailable',
                    slots: catalog,
                  })),
                ),
              ),
              startWith(LOADING_DOCS),
            )
          : this.agenda.catalogSlots().pipe(map(offeredDocs)),
      ),
    ),
    { initialValue: offeredDocs([]) },
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

  protected readonly docsLoading = computed(() => this.requiredDocsState().status === 'loading');

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
    () => !this.submitting() && !this.docsLoading() && this.missingDocs().length === 0,
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

  /** With fields missing it lists them instead of moving on; a policy block keeps the step. */
  next(): void {
    if (this.step() === 3) {
      return;
    }
    if (this.missingFields().length) {
      this.showMissing.set(true);
      return;
    }
    if (!(this.step() === 1 ? this.step1Valid() : this.step2Valid())) {
      return;
    }
    const nextStep = (this.step() + 1) as Step;
    this.showMissing.set(false);
    this.step.set(nextStep);
    if (nextStep > this.maxStepReached()) {
      this.maxStepReached.set(nextStep);
    }
  }

  prev(): void {
    if (this.step() > 1) {
      this.showMissing.set(false);
      this.step.update((s) => (s - 1) as Step);
    }
  }

  /** Any reached step is navigable; a new one still requires "Continuar". */
  goToStep(s: Step): void {
    if (s <= this.maxStepReached()) {
      this.showMissing.set(false);
      this.step.set(s);
    }
  }

  onFileChange(index: number, event: Event): void {
    const input = event.target as HTMLInputElement;
    this.setFile(index, input.files?.[0] ?? null);
    // Otherwise picking the same file twice doesn't fire (change).
    input.value = '';
  }

  protected readonly dragOverIndex = signal<number | null>(null);

  onDragOver(event: DragEvent, index: number): void {
    event.preventDefault();
    this.dragOverIndex.set(index);
  }

  /** Moving over the slot's own children also fires dragleave: only leaving the slot counts. */
  onDragLeave(event: DragEvent): void {
    const slot = event.currentTarget as HTMLElement;
    if (!slot.contains(event.relatedTarget as Node | null)) this.dragOverIndex.set(null);
  }

  onDrop(event: DragEvent, index: number): void {
    event.preventDefault();
    this.dragOverIndex.set(null);
    const file = event.dataTransfer?.files?.[0];
    if (file) this.setFile(index, file);
  }

  private setFile(index: number, file: File | null): void {
    const error = file ? fileTypeError(file) : null;
    this.docSlots.update((slots) => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file: error ? null : file, error };
      return updated;
    });
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
    const base = this.streetAddress().trim();
    return this.crossStreets().trim() ? `${base} (entre ${this.crossStreets()})` : base;
  }

  submit(): void {
    const type = this.selectedType();
    const policy = this.selectedPolicy();
    if (this.missingDocs().length) {
      this.showMissingDocs.set(true);
      return;
    }
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
      province: this.selectedProvince() || undefined,
      locality: this.selectedLocality() || undefined,
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
        this.submitError.set(
          err.status === 503
            ? 'No pudimos registrar tu denuncia en este momento. Probá de nuevo en unos minutos.'
            : err.error?.detail || 'Error al crear el caso',
        );
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
