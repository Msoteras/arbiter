import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { ExpedienteResponse } from '../../../core/models/expediente';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { DocUploadComponent } from '../../../shared/ui/doc-upload/doc-upload.component';
import { CaseDocumentsComponent } from '../../expedientes/case-documents/case-documents.component';

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse }
  | { status: 'error'; httpStatus: number };

/**
 * Los documentos del expediente, del lado del asegurado: lo que ya envió y —solo si la aseguradora
 * se lo pidió (AWAITING_DOCUMENTATION)— la carga de lo que falta. Reusa app-case-documents para la
 * lista y app-doc-upload para la mecánica de carga (valida, arrastra, POST /cases/{id}/documents →
 * re-dispara la clasificación). Al enviarse, vuelve al seguimiento, que refleja el nuevo estado.
 *
 * <p>Se entra por dos puertas distintas y la pantalla no puede hablarles igual: "cargar
 * documentación" cuando la aseguradora le pidió algo, y "ver mis documentos" desde cualquier otro
 * estado, donde solo hay lista — subir algo no pedido re-dispararía una clasificación de la nada.
 */
@Component({
  selector: 'app-documentacion',
  imports: [RouterLink, CardComponent, DocUploadComponent, CaseDocumentsComponent],
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
  protected readonly insurerSlug = this.route.snapshot.queryParamMap.get('insurer');

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

  /**
   * La aseguradora le pidió documentación puntual: es el único momento en que tiene sentido
   * ofrecer la carga. Subir un documento re-dispara la clasificación (POST /cases/{id}/documents),
   * así que fuera de este estado no hay nada que "sumar" — solo se muestra lo que ya envió.
   */
  protected readonly needsDocs = computed(() => this.data()?.status === 'AWAITING_DOCUMENTATION');

  protected onUploaded(): void {
    // El backend ya recibió los documentos y re-encoló la clasificación: volvemos al
    // seguimiento, que va a mostrar el estado actualizado.
    this.router.navigate(['/portal/cases', this.caseId], {
      queryParams: this.insurerSlug ? { insurer: this.insurerSlug } : {},
    });
  }
}
