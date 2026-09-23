import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  computed,
  inject,
  signal,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  map,
  of,
  startWith,
  switchMap,
} from 'rxjs';

import { ExpedienteService } from '../expediente.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { estadoLabel, estadoTone } from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';

const MIN_CHARS = 2;
/** The rest is reachable through "Ver todos los resultados" in the inbox. */
const MAX_RESULTS = 6;

interface SearchState {
  items: ExpedienteResponse[];
  loading: boolean;
  failed: boolean;
}

const IDLE: SearchState = { items: [], loading: false, failed: false };

/**
 * Uses the same `GET /cases?q=` as the inbox (see `CaseSpecifications.freeText`); it can't search by
 * analyst, so the placeholder must not promise it. Not `app-input`: the kit input is calibrated for
 * light surfaces and this sits on the dark top bar, styled with the `--chrome-*` roles.
 */
@Component({
  selector: 'app-global-search',
  imports: [BadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="gsearch" role="search">
      <svg class="gsearch-icon" width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
        <circle cx="7" cy="7" r="5" fill="none" stroke="currentColor" stroke-width="1.6" />
        <line
          x1="10.6"
          y1="10.6"
          x2="14"
          y2="14"
          stroke="currentColor"
          stroke-width="1.6"
          stroke-linecap="round"
        />
      </svg>

      <input
        class="gsearch-field"
        type="search"
        role="combobox"
        autocomplete="off"
        aria-label="Buscar expedientes"
        [attr.aria-expanded]="panelOpen()"
        aria-controls="gsearch-panel"
        placeholder="Buscar por N° de expediente, asegurado o N° de póliza…"
        [value]="query()"
        (input)="onInput($any($event.target).value)"
        (focus)="focused.set(true)"
        (keydown.enter)="submit()"
      />

      @if (query()) {
        <button type="button" class="gsearch-clear" aria-label="Limpiar búsqueda" (click)="clear()">
          ×
        </button>
      }

      @if (panelOpen()) {
        <div class="gsearch-panel" id="gsearch-panel" role="listbox">
          @if (state().loading) {
            <p class="gsearch-note">Buscando…</p>
          } @else if (state().failed) {
            <p class="gsearch-note">No pudimos buscar. Probá de nuevo en un momento.</p>
          } @else if (state().items.length === 0) {
            <p class="gsearch-note">Sin resultados para “{{ query().trim() }}”.</p>
          } @else {
            <ul class="gsearch-list">
              @for (c of state().items; track c.id) {
                <li>
                  <button type="button" role="option" class="gsearch-item" (click)="goToCase(c.id)">
                    <span class="gsearch-item-id mono">EXP-{{ c.id }}</span>
                    <span class="gsearch-item-meta">
                      {{ c.insuredName ?? c.insuredId }} · {{ c.claimCause }}
                    </span>
                    <app-badge [tone]="tone(c.status)">{{ label(c.status) }}</app-badge>
                  </button>
                </li>
              }
            </ul>
            <button type="button" class="gsearch-all" (click)="submit()">
              Ver todos los resultados en la bandeja ›
            </button>
          }
        </div>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }

    .gsearch {
      position: relative;
      display: flex;
      align-items: center;
      gap: var(--space-2);
      padding: 0 var(--space-3);
      height: 34px;
      border-radius: var(--radius-pill);
      background: var(--chrome-item-active);
      border: 1px solid transparent;
      color: var(--chrome-muted);
      transition: border-color var(--dur-1) ease;
    }
    .gsearch:focus-within {
      border-color: var(--border-focus);
    }
    .gsearch-icon {
      flex: none;
    }
    .gsearch-field {
      flex: 1 1 auto;
      min-width: 0;
      font: inherit;
      /* 16px on mobile avoids the iOS focus zoom; smaller on desktop so the placeholder fits. */
      font-size: var(--font-size-lg);
      background: none;
      border: none;
      outline: none;
      color: var(--chrome-fg);
    }
    @media (min-width: 641px) {
      .gsearch-field {
        font-size: var(--font-size-body);
      }
      .gsearch-field::placeholder {
        font-size: var(--font-size-xs);
      }
    }
    .gsearch-field::placeholder {
      color: var(--chrome-muted);
    }
    /* Hide the native clear button; we render our own. */
    .gsearch-field::-webkit-search-cancel-button {
      display: none;
    }
    .gsearch-clear {
      flex: none;
      border: none;
      background: none;
      cursor: pointer;
      font-size: var(--font-size-lg);
      line-height: 1;
      color: var(--chrome-muted);
      padding: 0 var(--space-1);
    }
    .gsearch-clear:hover {
      color: var(--chrome-fg);
    }

    /* Light surface (content, not chrome); anchored right and grows leftwards, wider than the field. */
    .gsearch-panel {
      position: absolute;
      top: calc(100% + var(--space-2));
      right: 0;
      width: max(100%, 380px);
      max-width: 88vw;
      z-index: 60;
      padding: var(--space-1);
      background: var(--surface);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-card);
      box-shadow: var(--shadow-modal);
    }
    .gsearch-note {
      margin: 0;
      padding: var(--space-3);
      font-size: var(--font-size-sm);
      color: var(--text-muted);
    }
    .gsearch-list {
      margin: 0;
      padding: 0;
      list-style: none;
      max-height: 320px;
      overflow-y: auto;
    }
    .gsearch-item {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      width: 100%;
      font: inherit;
      text-align: left;
      padding: var(--space-2) var(--space-3);
      border: none;
      border-radius: var(--radius-ctl);
      background: none;
      cursor: pointer;
    }
    .gsearch-item:hover {
      background: var(--surface-sunken);
    }
    .gsearch-item-id {
      flex: none;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    .gsearch-item-meta {
      flex: 1 1 auto;
      min-width: 0;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .gsearch-all {
      display: block;
      width: 100%;
      font: inherit;
      text-align: left;
      padding: var(--space-2) var(--space-3);
      margin-top: var(--space-1);
      border: none;
      border-top: 1px solid var(--border-subtle);
      background: none;
      font-size: var(--font-size-sm);
      color: var(--accent-fg);
      cursor: pointer;
    }
    .gsearch-all:hover {
      background: var(--surface-sunken);
    }
  `,
})
export class GlobalSearchComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly router = inject(Router);
  private readonly expedientes = inject(ExpedienteService);

  protected readonly query = signal('');
  protected readonly focused = signal(false);

  protected readonly state = toSignal(
    toObservable(this.query).pipe(
      map((q) => q.trim()),
      debounceTime(300),
      distinctUntilChanged(),
      switchMap((q) => {
        if (q.length < MIN_CHARS) {
          return of(IDLE);
        }
        return this.expedientes.list({ q, size: MAX_RESULTS, sort: 'id,desc' }).pipe(
          map((page) => ({ items: page.content, loading: false, failed: false })),
          catchError(() => of({ items: [], loading: false, failed: true })),
          // After the debounce, so "Buscando…" only shows once the request actually goes out.
          startWith({ items: [], loading: true, failed: false }),
        );
      }),
    ),
    { initialValue: IDLE },
  );

  protected readonly panelOpen = computed(
    () => this.focused() && this.query().trim().length >= MIN_CHARS,
  );

  protected onInput(value: string): void {
    this.query.set(value);
    this.focused.set(true);
  }

  protected clear(): void {
    this.query.set('');
  }

  protected goToCase(id: number): void {
    this.close();
    this.router.navigate(['/cases', id]);
  }

  /** The inbox picks the search up from the query param. */
  protected submit(): void {
    const q = this.query().trim();
    if (q.length < MIN_CHARS) return;
    this.close();
    this.router.navigate(['/inbox'], { queryParams: { q } });
  }

  private close(): void {
    this.query.set('');
    this.focused.set(false);
  }

  protected label(status: string): string {
    return estadoLabel(status);
  }
  protected tone(status: string): StatusTone {
    return estadoTone(status);
  }

  @HostListener('document:click', ['$event'])
  protected onDocumentClick(event: MouseEvent): void {
    if (this.focused() && !this.host.nativeElement.contains(event.target as Node)) {
      this.focused.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    this.focused.set(false);
  }
}
