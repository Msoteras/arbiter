import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { InsurerLogo, InsurerLogoService } from '../../../core/services/insurer-logo.service';
import { formatDate } from '../../../core/util/datetime';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { LogoComponent } from '../../../shared/ui/logo/logo.component';
import { ToastService } from '../../../shared/ui/toast/toast.service';

/** Mirrors InsurerLogoService (auth-service): checked here too so a wrong file fails at once. */
const ACCEPTED_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/svg+xml'];
const MAX_BYTES = 256 * 1024;

const TYPE_LABELS: Record<string, string> = {
  'image/png': 'PNG',
  'image/jpeg': 'JPG',
  'image/webp': 'WebP',
  'image/svg+xml': 'SVG',
};

/**
 * The insurer's logo, the one its people see in the app bar. A section of the Rules screen
 * ("Otras configuraciones"), next to the other insurer-wide settings.
 */
@Component({
  selector: 'app-brand',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonComponent, CardComponent, InfoTipComponent, LogoComponent],
  templateUrl: './brand.component.html',
  styleUrl: './brand.component.scss',
})
export class BrandComponent {
  private readonly logoService = inject(InsurerLogoService);
  private readonly toast = inject(ToastService);
  private readonly session = inject(AuthSessionService);

  protected readonly accept = ACCEPTED_TYPES.join(',');
  protected readonly logo = this.logoService.logo;
  protected readonly insurerName = computed(() => this.session.session()?.insurerName ?? '');
  protected readonly monogram = computed(() => initials(this.insurerName()));
  protected readonly userInitials = computed(() => {
    const s = this.session.session();
    return s ? initials(`${s.nombre} ${s.apellido}`) : '';
  });

  protected readonly uploading = signal(false);
  protected readonly removing = signal(false);
  protected readonly dragging = signal(false);
  protected readonly error = signal<string | null>(null);

  /** "SVG · 12 KB · subido el 14/10/2026". */
  protected details(logo: InsurerLogo): string {
    const type = TYPE_LABELS[logo.type] ?? logo.type;
    return `${type} · ${fileSize(logo.bytes)} · subido el ${formatDate(logo.uploadedAt)}`;
  }

  protected onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    // Cleared so picking the same file again (after an error) fires `change` again.
    input.value = '';
    if (file) this.upload(file);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) this.upload(file);
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  protected remove(): void {
    this.error.set(null);
    this.removing.set(true);
    this.logoService.remove().subscribe({
      next: () => {
        this.removing.set(false);
        this.toast.show('Quitaste el logo.', 'ok');
      },
      error: (err: HttpErrorResponse) => {
        this.removing.set(false);
        this.error.set(problemDetail(err) ?? 'No se pudo quitar el logo. Probá de nuevo.');
      },
    });
  }

  private upload(file: File): void {
    this.error.set(null);
    if (!ACCEPTED_TYPES.includes(file.type)) {
      this.error.set('El logo tiene que ser PNG, JPG, WebP o SVG.');
      return;
    }
    if (file.size > MAX_BYTES) {
      this.error.set('El logo pesa más de 256 KB. Subí una versión más liviana.');
      return;
    }
    this.uploading.set(true);
    this.logoService.upload(file).subscribe({
      next: () => {
        this.uploading.set(false);
        this.toast.show('Logo actualizado.', 'ok');
      },
      error: (err: HttpErrorResponse) => {
        this.uploading.set(false);
        this.error.set(problemDetail(err) ?? 'No se pudo subir el logo. Probá de nuevo.');
      },
    });
  }
}

/** Initials of the first two words: "Seguros del Sur" → "SD". */
function initials(name: string): string {
  const [first = '', second = ''] = name.trim().split(/\s+/);
  return `${first[0] ?? ''}${second[0] ?? ''}`.toUpperCase();
}

function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  return `${kb < 10 ? kb.toFixed(1).replace('.', ',') : Math.round(kb)} KB`;
}

/** The backend's own wording for a rejected file (400); anything else gets the generic notice. */
function problemDetail(err: HttpErrorResponse): string | null {
  const detail = (err.error as { detail?: unknown } | null)?.detail;
  return typeof detail === 'string' && err.status === 400 ? detail : null;
}
