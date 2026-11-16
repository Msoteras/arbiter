import { Injectable, computed, signal } from '@angular/core';

/** Same cut as `media.sidebar-sheet` in _media.scss. */
const SHEET_QUERY = '(max-width: 767.98px)';
const EXPANDED_KEY = 'arbiter.nav-open';

/**
 * State of the app sidebar, shared by the sidebar, its items and the trigger in the top bar (the
 * role of shadcn/ui's SidebarProvider). On wide screens the sidebar is part of the layout and
 * folds down to its icons; the choice is remembered. Below the sheet cut it leaves the layout and
 * opens over the content, always starting closed.
 */
@Injectable({ providedIn: 'root' })
export class SidebarService {
  private readonly sheetMql =
    typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(SHEET_QUERY) : null;

  readonly isMobile = signal(this.sheetMql?.matches ?? false);
  /** Desktop preference: full width (true) or icons only (false). */
  readonly expanded = signal(this.storedExpanded());
  /** Phone sheet: open over the content. */
  readonly mobileOpen = signal(false);

  /** Icons-only rail: a desktop state, the phone sheet always shows the labels. */
  readonly collapsed = computed(() => !this.isMobile() && !this.expanded());
  /** Whether the navigation can be seen at all: drives aria-expanded on the trigger. */
  readonly visible = computed(() => (this.isMobile() ? this.mobileOpen() : true));

  constructor() {
    this.sheetMql?.addEventListener('change', (e) => {
      this.isMobile.set(e.matches);
      this.mobileOpen.set(false);
    });
  }

  toggle(): void {
    if (this.isMobile()) {
      this.mobileOpen.update((open) => !open);
      return;
    }
    const next = !this.expanded();
    this.expanded.set(next);
    try {
      localStorage.setItem(EXPANDED_KEY, String(next));
    } catch {
      // Storage blocked: the choice lasts until reload.
    }
  }

  closeMobile(): void {
    this.mobileOpen.set(false);
  }

  private storedExpanded(): boolean {
    try {
      return localStorage.getItem(EXPANDED_KEY) !== 'false';
    } catch {
      return true;
    }
  }
}
