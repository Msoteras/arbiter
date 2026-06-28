import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';

@Component({
  selector: 'app-bandeja',
  imports: [RouterLink, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bandeja">
      <h1 class="title">Bandeja de expedientes</h1>
      <p class="muted">
        El backend todavía no expone un listado (solo
        <span class="mono">GET /api/v1/expedientes/&#123;id&#125;</span>). Abrí un expediente por id:
      </p>

      <div class="navrow">
        <input #idInput class="id-input" type="number" min="1" placeholder="N° de expediente" />
        <button class="btn" (click)="open(idInput.value)">Abrir</button>
      </div>

      <p class="muted demo">
        Atajos de demo:
        @for (id of demoIds; track id) {
          <a class="chip" [routerLink]="['/expedientes', id]">#{{ id }}</a>
        }
      </p>

      <app-empty-state message="Listado de expedientes" />
    </div>
  `,
  styles: `
    .bandeja { padding: 22px 24px 40px; max-width: 760px; }
    .title { margin: 0 0 8px; font-size: 22px; font-weight: 600; }
    .muted { color: var(--c-muted); font-size: 13px; }
    .navrow { display: flex; gap: 8px; margin: 14px 0; }
    .id-input { padding: 7px 10px; border: 1px solid var(--c-border-3); border-radius: var(--radius-ctl); font: inherit; width: 180px; }
    .btn { padding: 7px 16px; border: 1px solid var(--c-ink); background: var(--c-ink); color: #fff; border-radius: var(--radius-ctl); cursor: pointer; font: inherit; }
    .btn:hover { background: #000; }
    .demo { margin-bottom: 16px; }
    .chip { display: inline-block; margin-left: 6px; padding: 2px 9px; border: 1px solid var(--c-border-3); border-radius: var(--radius-pill); text-decoration: none; color: var(--c-ink-2); font-family: var(--font-mono); font-size: 12px; }
    .chip:hover { border-color: var(--c-ink); }
  `,
})
export class BandejaComponent {
  private readonly router = inject(Router);
  protected readonly demoIds = [1, 2, 3, 4];

  open(value: string): void {
    const id = Number(value);
    if (id > 0) {
      this.router.navigate(['/expedientes', id]);
    }
  }
}
