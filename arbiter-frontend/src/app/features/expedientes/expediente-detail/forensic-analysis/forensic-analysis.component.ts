import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnDestroy, inject, input, signal, effect } from '@angular/core';

import { ExpedienteService } from '../../expediente.service';
import {
  ImageForensicFinding,
  ImageForensicInternalMatch,
  ImageForensicReport,
  ImageForensicWebFinding,
  forensicAlertLevel,
  forensicFindingIsClean,
  typeFromMatchedFilename,
} from '../../../../core/models/forensic';
import { CardComponent } from '../../../../shared/ui/card/card.component';
import { EmptyStateComponent } from '../../../../shared/ui/empty-state/empty-state.component';
import { SeverityLabelComponent } from '../../../../shared/ui/severity-label/severity-label.component';

/** Mismo diccionario tipo→label que doc-upload / nueva-denuncia (H0009 no agrega tipos nuevos). */
const DOC_TYPE_LABELS: Record<string, string> = {
  police_report: 'Denuncia policial',
  item_photo: 'Foto del bien',
  invoice: 'Factura de compra',
  quote: 'Presupuesto de reparación',
};

/**
 * Tab "Análisis forense" del panel del analista (H0009). Cruza cada hallazgo de
 * `ImageForensicReport` con el adjunto real del expediente para mostrar la imagen
 * analizada junto a sus coincidencias (ver docs/frontend-analisis-forense.md):
 *
 * - Coincidencia interna (otro siniestro): se muestra la imagen del siniestro previo
 *   lado a lado con la propia, más el % de similitud (comparación directa).
 * - Coincidencia web: se listan las páginas encontradas como links externos, a modo de
 *   verificación (no se aloja ninguna imagen de terceros).
 *
 * Todo hallazgo es una sugerencia — el analista decide siempre (ninguna imagen se
 * rechaza automáticamente).
 */
@Component({
  selector: 'app-forensic-analysis',
  imports: [DecimalPipe, CardComponent, EmptyStateComponent, SeverityLabelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './forensic-analysis.component.html',
  styleUrl: './forensic-analysis.component.scss',
})
export class ForensicAnalysisComponent implements OnDestroy {
  private readonly service = inject(ExpedienteService);

  readonly caseId = input.required<number>();
  readonly report = input.required<ImageForensicReport | null>();

  /** label del finding -> object URL del blob ya descargado. */
  protected readonly imageUrls = signal<Record<string, string>>({});
  protected readonly imageLoadFailed = signal<Record<string, boolean>>({});

  /** clave de match interno (ver `matchKey`) -> object URL de la imagen del siniestro previo. */
  protected readonly matchedImageUrls = signal<Record<string, string>>({});
  protected readonly matchedImageLoadFailed = signal<Record<string, boolean>>({});

  private activeObjectUrls: string[] = [];

  private readonly loadEffect = effect(() => {
    const report = this.report();
    const caseId = this.caseId();
    this.resetImages();
    if (report && report.findings.length > 0) {
      this.loadImages(caseId, report.findings);
      this.loadMatchedImages(report.findings);
    }
  });

  protected readonly alertLevel = forensicAlertLevel;
  protected readonly isClean = forensicFindingIsClean;

  protected docLabel(type: string): string {
    return DOC_TYPE_LABELS[type] ?? type;
  }

  protected webFound(web: ImageForensicWebFinding | null): boolean {
    return !!web && (web.fullMatches > 0 || web.partialMatches > 0 || web.pages.length > 0);
  }

  /** Clave estable para identificar la imagen de un match interno en los signals de arriba. */
  protected matchKey(match: ImageForensicInternalMatch): string {
    return `${match.matchedCaseId}:${match.matchedFilename}`;
  }

  private resetImages(): void {
    this.activeObjectUrls.forEach((url) => URL.revokeObjectURL(url));
    this.activeObjectUrls = [];
    this.imageUrls.set({});
    this.imageLoadFailed.set({});
    this.matchedImageUrls.set({});
    this.matchedImageLoadFailed.set({});
  }

  private loadImages(caseId: number, findings: ImageForensicFinding[]): void {
    this.service.listDocuments(caseId).subscribe({
      next: (docs) => {
        for (const finding of findings) {
          // `finding.filename` es hoy el `type` del documento (ver core/models/forensic.ts),
          // y el tipo es único por expediente — alcanza para cruzar 1:1 con el adjunto real.
          const doc = docs.find((d) => d.type === finding.filename);
          if (!doc) {
            this.imageLoadFailed.update((m) => ({ ...m, [finding.label]: true }));
            continue;
          }
          this.service.downloadDocumentBlob(caseId, doc.id).subscribe({
            next: (blob) => {
              const url = URL.createObjectURL(blob);
              this.activeObjectUrls.push(url);
              this.imageUrls.update((m) => ({ ...m, [finding.label]: url }));
            },
            error: () => this.imageLoadFailed.update((m) => ({ ...m, [finding.label]: true })),
          });
        }
      },
      error: () => {
        for (const finding of findings) {
          this.imageLoadFailed.update((m) => ({ ...m, [finding.label]: true }));
        }
      },
    });
  }

  /**
   * Descarga, para cada match interno, la imagen del siniestro previo (`matchedCaseId`) —
   * comparación lado a lado (docs/frontend-analisis-forense.md). El endpoint de documentos
   * no tiene chequeo de ownership hoy (ver CaseController), así que cualquier analista
   * autenticado puede traerla igual que la propia.
   */
  private loadMatchedImages(findings: ImageForensicFinding[]): void {
    for (const finding of findings) {
      for (const match of finding.internalMatches) {
        const key = this.matchKey(match);
        const approxType = typeFromMatchedFilename(match.matchedFilename);
        this.service.listDocuments(match.matchedCaseId).subscribe({
          next: (docs) => {
            const doc = docs.find((d) => d.type === approxType);
            if (!doc) {
              this.matchedImageLoadFailed.update((m) => ({ ...m, [key]: true }));
              return;
            }
            this.service.downloadDocumentBlob(match.matchedCaseId, doc.id).subscribe({
              next: (blob) => {
                const url = URL.createObjectURL(blob);
                this.activeObjectUrls.push(url);
                this.matchedImageUrls.update((m) => ({ ...m, [key]: url }));
              },
              error: () => this.matchedImageLoadFailed.update((m) => ({ ...m, [key]: true })),
            });
          },
          error: () => this.matchedImageLoadFailed.update((m) => ({ ...m, [key]: true })),
        });
      }
    }
  }

  ngOnDestroy(): void {
    this.activeObjectUrls.forEach((url) => URL.revokeObjectURL(url));
  }
}
