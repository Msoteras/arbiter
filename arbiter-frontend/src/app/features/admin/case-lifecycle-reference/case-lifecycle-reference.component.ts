import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { StatusMoves } from '../../../core/models/case-status';
import { fadeInUp } from '../../../shared/animations';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { CaseLifecycleComponent } from '../../../shared/ui/case-lifecycle/case-lifecycle.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { CaseService } from '../../cases/case.service';

/** Read-only insurer section in Reglas: the case state machine, the same for analyst and referent. */
@Component({
  selector: 'app-case-lifecycle-reference',
  imports: [CardComponent, CaseLifecycleComponent, EmptyStateComponent, InlineLoadingComponent],
  animations: [fadeInUp],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (failed()) {
      <app-empty-state message="No pudimos cargar los estados del expediente." />
    } @else if (moves(); as m) {
      <app-card heading="Ciclo del expediente" headingSize="lg" @fadeInUp>
        <app-case-lifecycle [transitions]="m" />
      </app-card>
    } @else {
      <app-inline-loading [size]="26" message="Cargando los estados…" />
    }
  `,
})
export class CaseLifecycleReferenceComponent {
  protected readonly moves = signal<StatusMoves[] | null>(null);
  protected readonly failed = signal(false);

  constructor() {
    inject(CaseService)
      .statusMoves()
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe({
        next: (m) => this.moves.set(m),
        error: () => this.failed.set(true),
      });
  }
}
