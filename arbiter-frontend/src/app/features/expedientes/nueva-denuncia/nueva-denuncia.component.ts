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
  scan,
  startWith,
  switchMap,
} from 'rxjs';

import { ExpedienteService, CaseCreateRequest } from '../expediente.service';
import { PolicyService } from '../policy.service';
import { DocumentAgendaService } from '../document-agenda.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { Policy } from '../../../core/models/policy';
import { ChipGroupComponent, ChipOption } from '../../../shared/ui/chip-group/chip-group.component';
import { addDays, isPoliceReportBeforeEvent, isTypedDate, todayIso } from '../../../core/util/datetime';
import { CASE_DOCUMENT_TYPES, CaseDocumentType, documentTypeLabel } from '../../../core/models/case-document';
import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { FilePreviewComponent } from '../../../shared/ui/file-preview/file-preview.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { SwitchComponent } from '../../../shared/ui/switch/switch.component';

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

/**
 * `mandatory` distingue la agenda REAL del referente (para ese ramo + hecho generador, algo
 * está configurado como requerido) de la caída al catálogo completo cuando no hay nada
 * configurado — ese catálogo no es una lista de obligatorios, es "podés adjuntar esto si te
 * sirve" para no dejar al asegurado sin poder subir nada. Antes de esta distinción el wizard le
 * decía a los dos casos lo mismo ("ayuda a agilizar"), que en el caso con agenda real es falso:
 * esos documentos son obligatorios, no una sugerencia.
 */
interface RequiredDocsState {
  mandatory: boolean;
  slots: readonly CaseDocumentType[];
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
  // `list` en loading es la tanda ANTERIOR, que se sigue mostrando mientras llega la nueva. Sin
  // esto, cualquier recarga vaciaba los chips y los volvía a dibujar: el titileo.
  | { status: 'loading'; list?: ClaimType[] }
  | { status: 'ok'; list: ClaimType[] };

type EligibilityState =
  | { status: 'idle' }
  // `previous` es el veredicto que seguía mostrándose mientras se revalida. Sin él, cada
  // rechequeo cambiaba el bloque por el loader "Verificando la póliza…" y lo devolvía: como ese
  // bloque está en el paso 1, justo arriba de los chips, el modal cambiaba de alto y titilaba.
  | { status: 'checking'; previous?: EligibilityState }
  | { status: 'ok' }
  | { status: 'blocked'; reason: string }
  // El precheck no respondió (red, 5xx, o un 400 real de contrato). Distinto de 'ok': no
  // sabemos si la póliza es elegible, no que sí lo sea. Sigue sin bloquear "Siguiente" — el
  // gate real del submit final sigue estando — pero avisa en vez de quedar en silencio.
  | { status: 'unknown' };

/** Hora representativa de cada franja: el medio, no el borde. */
const SLOT_TIMES: Record<string, string> = {
  madrugada: '03:00',
  manana: '09:00',
  tarde: '15:00',
  noche: '21:00',
};

/**
 * La franja a la que pertenece una hora ya cargada, para que el chip refleje el campo en vez de
 * competir con él. Los cortes siguen cómo se habla del día en castellano rioplatense: la tarde
 * arranca al mediodía y la noche cuando cae la luz, no cada seis horas exactas.
 */
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
  // completado sin reabrir la validación de "Continuar" cada vez.
  protected readonly maxStepReached = signal<Step>(1);
  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly submittedCase = signal<ExpedienteResponse | null>(null);

  // La identidad sale de la sesión: el asegurado ya está logueado/identificado, no
  // vuelve a tipear el DNI. (Cuando se integre Auth0, sale del JWT.)
  private readonly insuredId = this.session.insuredId();

  // Step 1 — pólizas del asegurado (de todas las aseguradoras) para elegir.
  // Atado a policiesRetry (no un Observable directo): un fetch que falla una vez (backend caído,
  // red) quedaba en 'error' para siempre, porque toSignal solo se suscribe una vez al construir el
  // componente — sin esta indirección no había forma de reintentar sin recargar la página entera.
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

  // Tecnología Portátil no ata la póliza a un equipo fijo como Celulares (el titular puede
  // denunciar cualquier notebook/tablet que tenga en ese momento, no siempre la que quedó
  // registrada en el alta) — autocompletar y bloquear el campo con el insuredItem de la póliza le
  // mentiría al asegurado sobre qué bien puede declarar.
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
      // El computed de arriba devuelve un objeto NUEVO en cada recálculo, así que toObservable lo
      // ve distinto aunque branch y policyNumber sean los mismos — y volvía a pedir la lista, con
      // su parpadeo de loading, cada vez que se rearmaba `policies()`. Se compara por valor.
      distinctUntilChanged(
        (a, b) => a?.branch === b?.branch && a?.policyNumber === b?.policyNumber,
      ),
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
      // Arrastra la lista ya cargada al estado de loading siguiente: al recargar, los chips se
      // quedan en pantalla en vez de desaparecer y volver.
      scan(
        (prev, next): ClaimTypesState =>
          next.status === 'loading' && prev.status === 'ok' ? { status: 'loading', list: prev.list } : next,
        { status: 'idle' } as ClaimTypesState,
      ),
    ),
    { initialValue: { status: 'idle' } as ClaimTypesState },
  );

  /** Solo cuando NO hay nada que mostrar: si ya hay chips, se recarga sin vaciarlos. */
  protected readonly claimTypesLoading = computed(() => {
    const s = this.claimTypesState();
    return s.status === 'loading' && !s.list?.length;
  });

  protected readonly claimTypes = computed<ClaimType[]>(() => {
    const s = this.claimTypesState();
    return s.status === 'ok' ? s.list : (s.status === 'loading' ? (s.list ?? []) : []);
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

  // eligibilityError()/eligibilityChecking() están declarados más abajo (dependen de
  // eligibilityCheck), pero como son signals el orden de declaración no importa para el computed.
  protected readonly step1Valid = computed(
    () =>
      !!this.selectedPolicy() &&
      !!this.selectedType() &&
      this.eligibilityError() === null &&
      !this.eligibilityChecking(),
  );

  // Tope del input de fecha: un siniestro no puede haber
  // "ocurrido" en el futuro. La regla real vive en el backend (CaseRequest la valida de
  // nuevo); esto es solo la ayuda visual del datepicker.
  protected readonly today = todayIso();

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
  /**
   * Whether the insured already filed the police report. Used to be answered by leaving the date
   * blank, which cannot tell "I haven't filed it yet" apart from "I skipped the field" — and the
   * reporting-deadline rule (D12) reads that same date, so the difference matters.
   *
   * <p>Starts ON, and only shows up for a claim cause whose schedule asks for a police report
   * (theft). For those, filing the claim already holding the report is the normal path — the
   * insured comes from the station — and the report is a MANDATORY document of the schedule, so
   * not having it is the exception and has to be declared as such. Defaulting to off presented
   * the exception as the norm, and made it a one-tap way of leaving D12 unevaluated.
   */
  protected readonly policeReportFiled = signal(true);

  /** Turning it off clears the date: a report that was un-declared must not reach the backend. */
  setPoliceReportFiled(filed: boolean): void {
    this.policeReportFiled.set(filed);
    if (!filed) {
      this.policeReportDate.set('');
      this.policeReportTime.set('');
    }
  }
  /**
   * Franjas horarias del atajo. La hora representativa es el medio de cada franja, no su borde:
   * elegir "Noche" pone 21:00 y no 19:00, así una franja no empuja el dato contra el límite de un
   * plazo (D11 cuenta horas desde el hecho) solo por haber sido elegida.
   *
   * El dato sigue siendo el campo de hora: el chip lo escribe, no lo reemplaza. Por eso la franja
   * se DERIVA de la hora cargada — si el asegurado la corrige a mano, el chip se acomoda solo en
   * vez de quedar marcando algo que ya no es cierto.
   */
  protected readonly timeSlots: readonly ChipOption[] = [
    { value: 'madrugada', label: 'Madrugada' },
    { value: 'manana', label: 'Mañana' },
    { value: 'tarde', label: 'Tarde' },
    { value: 'noche', label: 'Noche' },
  ];

  /**
   * The exact date behind each relative chip. Built once: the wizard is filled in one sitting, and
   * a claim reported across midnight is better off keeping the day it started on than having the
   * chips shift under the insured mid-form.
   */
  private readonly eventDateShortcuts: Record<string, string> = {
    hoy: this.today,
    ayer: addDays(this.today, -1),
    anteayer: addDays(this.today, -2),
  };

  /**
   * Relative shortcuts for the event date. The insured knows "ayer", not "2026-06-12", and making
   * them do that conversion is where they get it wrong. Three days cover the overwhelming majority
   * — a claim is reported within days, which is the same window the reporting deadline cares about.
   *
   * <p>Same contract as the time slots: the chip WRITES the date field, it does not replace it.
   * Anything older is typed in the field, which stays visible and is still the actual value.
   */
  protected readonly eventDateOptions: readonly ChipOption[] = [
    { value: 'hoy', label: 'Hoy' },
    { value: 'ayer', label: 'Ayer' },
    { value: 'anteayer', label: 'Anteayer' },
  ];

  /** Derived from the field, so typing a date by hand lights up the matching chip. */
  protected readonly eventDateShortcut = computed(() => {
    const date = this.eventDate();
    return Object.keys(this.eventDateShortcuts).find((k) => this.eventDateShortcuts[k] === date) ?? '';
  });

  selectEventDateShortcut(key: string): void {
    this.eventDate.set(this.eventDateShortcuts[key] ?? '');
  }

  /**
   * The police report date is offered RELATIVE to the event, not in absolute terms. That is what
   * makes the common path incapable of expressing a report filed before the claim it reports:
   * every option is on or after the event date. The coherence error stops being the usual way of
   * finding out about the rule and goes back to being the rare edge.
   */
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

  /**
   * The two date-coherence checks that need no backend round trip — instant, no debounce. Vigencia,
   * carencia y mora salen de {@link backendEligibility}: son las que dependen de datos que el
   * portal no trae de entrada (carencia) o de configuración del referente (mora, `onArrears`).
   */
  protected readonly dateCoherenceError = computed<string | null>(() => {
    const eventDate = this.eventDate();
    const policeDate = this.policeReportDate();
    // Mientras alguna de las dos siga a medio tipear no se valida nada: ver isTypedDate.
    if (!isTypedDate(eventDate) || !isTypedDate(policeDate)) {
      return null;
    }
    if (policeDate > this.today) {
      return 'La fecha de la denuncia policial no puede ser futura.';
    }
    const eventTime = this.eventTime();
    const policeTime = this.policeReportTime();
    if (!isPoliceReportBeforeEvent(eventDate, eventTime, policeDate, policeTime)) {
      return null;
    }
    // Mismo día: el error nombra las dos horas, que es el dato que hay que mirar. En días
    // distintos alcanza con las fechas.
    return policeDate === eventDate
      ? `Ese día el siniestro fue a las ${eventTime}, así que la denuncia policial no pudo ser a las ${policeTime}.`
      : 'La denuncia policial no puede ser anterior al siniestro. Revisá las dos fechas.';
  });

  /**
   * Un bloqueo real de la póliza (mora, vigencia, carencia): ahí no hay nada que el asegurado
   * pueda corregir en el formulario y el resto de los campos no tiene sentido. Se distingue del
   * error de coherencia de fechas, que se arregla justo ahí arriba — esconderle el formulario por
   * eso lo dejaba mirando un error sin forma de resolverlo.
   */
  protected readonly policyBlocked = computed(
    () => this.eligibilityError() !== null && this.dateCoherenceError() === null,
  );

  /**
   * Same gate `POST /cases` runs at intake (vigencia, carencia, mora — `PolicyEligibilityValidator`
   * via `POST /cases/eligibility`), so the insured finds out here instead of after filling out the
   * rest of the form and uploading documentation. Debounced: fires as the policy/dates settle, not
   * on every keystroke. Fails OPEN on a network error — the real gate at submit still enforces
   * vigencia/carencia (no external call needed for those) and rules-service being down already
   * fails open server-side for mora, so blocking the wizard over a transient check failure would
   * be strictly worse than the status quo.
   *
   * Fires as soon as there's a policy, `eventDate` or not: mora (`POLICY_STANDING`) doesn't need a
   * date, only vigencia/carencia do, and the backend already skips those when `eventDate` is
   * absent. That's what lets a rejected-for-arrears policy block right in step 1, at selection
   * time, instead of only after the insured fills in the event date in step 2.
   *
   * <p>Once `eventDate` has a value, it waits for `eventTime` too instead of defaulting it to
   * medianoche right away: con D13 comparando por hora exacta, chequear contra las 00:00 mientras
   * el asegurado todavía está por escribir la hora real tira un resultado que no es el que
   * corresponde — y al tipear la hora, un segundo chequeo lo pisa un instante después. Mejor
   * esperar los dos campos que mostrar una respuesta que va a cambiar sola.
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
        return {
          insuredId: policy.insuredId,
          policyNumber: policy.policyNumber,
          eventDate: eventDate ? eventDate + 'T' + eventTime + ':00' : undefined,
          policeReportAt: this.policeReportDate()
            ? this.policeReportDate() + 'T' + (this.policeReportTime() || '00:00') + ':00'
            : undefined,
        };
      }),
    ).pipe(
      // Tercer stream con el mismo patrón que claimTypesState y requiredDocsState: el computed de
      // arriba arma un objeto NUEVO en cada recálculo, así que toObservable lo ve distinto aunque
      // el pedido sea idéntico y se volvía a chequear de gusto. Se compara por contenido.
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
                  : { status: 'blocked', reason: res.reason ?? 'No se puede registrar la denuncia.' },
              ),
              startWith<EligibilityState>({ status: 'checking' }),
              catchError(() => of<EligibilityState>({ status: 'unknown' })),
            )
          : of<EligibilityState>({ status: 'idle' }),
      ),
      // El estado de revalidación arrastra el veredicto ya conocido, para que la pantalla no
      // vuelva a "no sé nada" cada vez que se rechequea.
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

  /**
   * El veredicto vigente: mientras se revalida, sigue siendo el anterior. Así el bloque de estado
   * de la póliza no parpadea entre "bloqueada" y el loader en cada rechequeo.
   */
  private readonly eligibilityVerdict = computed<EligibilityState>(() => {
    const check = this.eligibilityCheck();
    return check.status === 'checking' ? (check.previous ?? check) : check;
  });

  /** Solo cuando NO hay veredicto que mostrar: revalidar con uno previo no muestra el loader. */
  protected readonly eligibilityChecking = computed(() => {
    const check = this.eligibilityCheck();
    return check.status === 'checking' && !check.previous;
  });

  // El precheck falló (red, backend caído, un 400 real) y no hay forma de saber si la póliza es
  // elegible. No bloquea "Siguiente" — mismo criterio de fail-open que antes — pero el asegurado
  // se entera de que no se pudo confirmar, en vez de ver la nada silenciosa de un chequeo que
  // "pasó" sin haber corrido en realidad.
  protected readonly eligibilityUnknown = computed(() => this.eligibilityVerdict().status === 'unknown');

  protected readonly eligibilityError = computed<string | null>(() => {
    const dateError = this.dateCoherenceError();
    if (dateError) {
      return dateError;
    }
    const check = this.eligibilityVerdict();
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
      // eventLocation es @NotBlank en el backend, y ahora es solo la calle: exigirla puntualmente
      // en vez de "alguna de las cuatro partes cargadas", que dejaba pasar un submit con provincia
      // y localidad pero sin dirección.
      this.buildEventAddress().trim().length > 0,
  );

  // Step 3
  protected readonly docSlots = signal<DocSlot[]>(
    CASE_DOCUMENT_TYPES.map(({ type, label }) => ({ type, label, file: null, error: null })),
  );

  // El asegurado sube exactamente lo que el referente configuró como requerido para el ramo + hecho
  // generador elegidos (o el catálogo completo si esa combinación no tiene agenda). Al cambiar de
  // póliza o de hecho generador, se rearman los slots según esa agenda.
  private readonly requiredDocsState = toSignal(
    toObservable(
      computed(() => ({
        branch: this.selectedPolicy()?.branch ?? null,
        claimCause: this.selectedType()?.claimCause ?? null,
      })),
    ).pipe(
      // Mismo motivo que en claimTypesState, y acá el costo era peor que un parpadeo: cada emisión
      // vuelve a correr rebuildDocSlots, que rearma los slots desde cero y se lleva puestos los
      // archivos ya adjuntados.
      distinctUntilChanged((a, b) => a.branch === b.branch && a.claimCause === b.claimCause),
      switchMap(({ branch, claimCause }) =>
        branch && claimCause
          ? this.agenda.getForBranch(branch, claimCause).pipe(
              map((codes): RequiredDocsState =>
                codes.length
                  ? { mandatory: true, slots: codes.map((type) => ({ type, label: documentTypeLabel(type) })) }
                  : { mandatory: false, slots: CASE_DOCUMENT_TYPES },
              ),
              catchError(() => of<RequiredDocsState>({ mandatory: false, slots: CASE_DOCUMENT_TYPES })),
            )
          : of<RequiredDocsState>({ mandatory: false, slots: CASE_DOCUMENT_TYPES }),
      ),
    ),
    { initialValue: { mandatory: false, slots: CASE_DOCUMENT_TYPES } as RequiredDocsState },
  );
  private readonly rebuildDocSlots = effect(() => {
    const { slots } = this.requiredDocsState();
    this.docSlots.set(slots.map(({ type, label }) => ({ type, label, file: null, error: null })));
  });

  /** Si hay agenda real configurada para este ramo + hecho generador: la documentación no es
   *  una sugerencia, es requisito para poder evaluar el caso (ver RequiredDocsState). */
  protected readonly docsRequired = computed(() => this.requiredDocsState().mandatory);

  protected readonly docsCount = computed(() => this.docSlots().filter((d) => d.file).length);

  /**
   * Si el ramo pide constancia de denuncia policial, entonces el hecho generador la lleva y tiene
   * sentido preguntar cuándo se hizo. Se deriva de la agenda documental del referente —la misma
   * fuente que arma los slots de adjuntos— en vez de una lista propia de hechos generadores: así
   * el día que el referente saque `police_report` de un ramo, el campo desaparece solo.
   */
  protected readonly requiresPoliceReport = computed(() =>
    this.requiredDocsState().slots.some(({ type }) => type === 'police_report'),
  );

  /**
   * Switching to a claim cause that needs no police report (rotura, caída: there is no crime to
   * report) drops whatever was declared for the previous one. Without this, reporting a theft,
   * going back and picking an accidental breakage kept sending a `policeReportAt` the new cause
   * has no business carrying — and left the toggle off for the next theft.
   */
  private readonly resetPoliceReport = effect(() => {
    if (!this.requiresPoliceReport()) {
      untracked(() => {
        this.policeReportFiled.set(true);
        this.policeReportDate.set('');
        this.policeReportTime.set('');
      });
    }
  });

  /** Los hechos generadores como los consume `app-chip-group`: la clave identifica, el label se lee. */
  protected readonly claimTypeOptions = computed<ChipOption[]>(() =>
    this.claimTypes().map((t) => ({ value: t.key, label: t.label })),
  );

  /**
   * El chip devuelve la clave; el resto del wizard trabaja con el {@link ClaimType} entero (usa
   * `claimCause` para la agenda documental y el alta), así que se resuelve acá.
   */
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

  /**
   * Dirección a nivel calle, sin localidad ni provincia — esas dos viajan en sus propios campos
   * del request y se guardan en `cases.locality`/`cases.province`. Antes esto concatenaba las
   * cuatro partes en un solo string y era lo único que llegaba al backend.
   */
  private buildEventAddress(): string {
    const base = this.calleNumero().trim();
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
      // Solo la dirección a nivel calle. Localidad y provincia van aparte, en sus propios campos:
      // concatenar las cuatro partes acá dejaba cases.locality/province en null y la ubicación
      // como prosa imposible de filtrar o agrupar.
      eventLocation: this.buildEventAddress(),
      province: this.provincia() || undefined,
      locality: this.localidad() || undefined,
      // Solo si se declaró: la columna es nullable y "no hubo denuncia policial" es un caso
      // legítimo, distinto de "hubo pero no sé cuándo". Mandar una fecha inventada sería peor
      // que no mandar nada, porque la regla del plazo la evaluaría como si fuera real.
      policeReportAt: this.policeReportDate()
        ? this.policeReportDate() + 'T' + (this.policeReportTime() || '00:00') + ':00'
        : undefined,
      claimedAmount: this.claimedAmount() ? Number(this.claimedAmount()) : undefined,
      // PEP y consentimiento de imágenes ya no viajan en la denuncia: son datos de la PERSONA,
      // no del siniestro (viven en Insured, no en Case). PEP sale de la póliza/KYC de la
      // aseguradora; el consentimiento se da una vez en el onboarding (H0009) y se cambia desde
      // "Mi perfil". El backend los ignora si se mandan.
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
