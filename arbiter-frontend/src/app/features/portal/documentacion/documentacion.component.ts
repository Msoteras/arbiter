import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { ExpedienteResponse } from '../../../core/models/expediente';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { DocUploadComponent } from '../../../shared/ui/doc-upload/doc-upload.component';

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse }
  | { status: 'error'; httpStatus: number };

/**
 * Documentación adicional del asegurado: pantalla dedicada para subir los documentos
 * faltantes de un expediente. Reusa app-doc-upload para la mecánica de carga (valida,
 * arrastra, POST /cases/{id}/documents → re-dispara la clasificación). Al enviarse,
 * vuelve al seguimiento, que refleja el nuevo estado.
 */
@Component({
  selector: 'app-documentacion',
  imports: [RouterLink, CardComponent, DocUploadComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './documentacion.component.html',
  styleUrl: './documentacion.component.scss',
})
export class DocumentacionComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(ExpedienteService);

  protected readonly caseId = Number(this.route.snapshot.paramMap.get('id'));
  /**
   * Mismo motivo que en seguimiento: un asegurado con pólizas en más de una compañía puede tener
   * este expediente en un tenant distinto del default de su sesión. La viene arrastrando la URL
   * desde el link que trajo hasta acá (inicio, mis siniestros, seguimiento).
   */
  protected readonly insurerSlug = this.route.snapshot.queryParamMap.get('aseguradora');

  private readonly state = toSignal(
    this.route.paramMap.pipe(
      map((params) => params.get('id') ?? ''),
      switchMap((id) =>
        this.service.getById(id, this.insurerSlug).pipe(
          map((data): LoadState => ({ status: 'ok', data })),
          startWith<LoadState>({ status: 'loading' }),
          catchError((err: HttpErrorResponse) =>
            of<LoadState>({ status: 'error', httpStatus: err.status }),
          ),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as LoadState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly data = computed<ExpedienteResponse | null>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : null;
  });

  protected onUploaded(): void {
    // El backend ya recibió los documentos y re-encoló la clasificación: volvemos al
    // seguimiento, que va a mostrar el estado actualizado.
    this.router.navigate(['/portal/cases', this.caseId], {
      queryParams: this.insurerSlug ? { aseguradora: this.insurerSlug } : {},
    });
  }
}
