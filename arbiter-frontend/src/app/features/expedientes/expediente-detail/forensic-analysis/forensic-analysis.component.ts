import { DecimalPipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  inject,
  input,
  signal,
  effect,
} from '@angular/core';

import { ExpedienteService } from '../../expediente.service';
import {
  ImageForensicFinding,
  ImageForensicInternalMatch,
  ImageForensicReport,
  ImageForensicWebFinding,
  forensicAlertLevel,
  forensicFindingIsClean,
} from '../../../../core/models/forensic';
import { CardComponent } from '../../../../shared/ui/card/card.component';
import { EmptyStateComponent } from '../../../../shared/ui/empty-state/empty-state.component';
import { SeverityLabelComponent } from '../../../../shared/ui/severity-label/severity-label.component';
import { SpinnerComponent } from '../../../../shared/ui/spinner/spinner.component';

const DOC_TYPE_LABELS: Record<string, string> = {
  police_report: 'Denuncia policial',
  item_photo: 'Foto del bien',
  invoice: 'Factura de compra',
  quote: 'Presupuesto de reparación',
};

/**
 * Shows each analyzed image next to its matches: internal matches side by side with the earlier
 * claim's image; web matches only as external links (no third-party image is hosted).
 */
@Component({
  selector: 'app-forensic-analysis',
  imports: [
    DecimalPipe,
    CardComponent,
    EmptyStateComponent,
    SeverityLabelComponent,
    SpinnerComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './forensic-analysis.component.html',
  styleUrl: './forensic-analysis.component.scss',
})
export class ForensicAnalysisComponent implements OnDestroy {
  private readonly service = inject(ExpedienteService);

  readonly caseId = input.required<number>();
  readonly report = input.required<ImageForensicReport | null>();

  /** Finding label -> object URL. */
  protected readonly imageUrls = signal<Record<string, string>>({});
  protected readonly imageLoadFailed = signal<Record<string, boolean>>({});
  /** Finding label -> original filename, used to name the download. */
  protected readonly imageFilenames = signal<Record<string, string>>({});

  /** `matchKey` -> object URL of the earlier claim's image. */
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

  /** Keyed by type, not filename: `case_documents` is UNIQUE (case_id, type), filenames may repeat. */
  protected matchKey(match: ImageForensicInternalMatch): string {
    return `${match.matchedCaseId}:${match.matchedDocumentType}`;
  }

  private resetImages(): void {
    this.activeObjectUrls.forEach((url) => URL.revokeObjectURL(url));
    this.activeObjectUrls = [];
    this.imageUrls.set({});
    this.imageLoadFailed.set({});
    this.imageFilenames.set({});
    this.matchedImageUrls.set({});
    this.matchedImageLoadFailed.set({});
  }

  private loadImages(caseId: number, findings: ImageForensicFinding[]): void {
    this.service.listDocuments(caseId).subscribe({
      next: (docs) => {
        for (const finding of findings) {
          // UNIQUE (case_id, type): the type maps 1:1 to the uploaded document.
          const doc = docs.find((d) => d.type === finding.documentType);
          if (!doc) {
            this.imageLoadFailed.update((m) => ({ ...m, [finding.label]: true }));
            continue;
          }
          this.service.downloadDocument(caseId, doc.id).subscribe({
            next: (blob) => {
              const url = URL.createObjectURL(blob);
              this.activeObjectUrls.push(url);
              this.imageUrls.update((m) => ({ ...m, [finding.label]: url }));
              this.imageFilenames.update((m) => ({ ...m, [finding.label]: doc.filename }));
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
   * Fetches documents from ANOTHER case: allowed because `CaseAccessPolicy.assertCanRead` only
   * restricts the insured to their own cases; the analyst can read every case of the insurer.
   */
  private loadMatchedImages(findings: ImageForensicFinding[]): void {
    for (const finding of findings) {
      for (const match of finding.internalMatches) {
        const key = this.matchKey(match);
        this.service.listDocuments(match.matchedCaseId).subscribe({
          next: (docs) => {
            const doc = docs.find((d) => d.type === match.matchedDocumentType);
            if (!doc) {
              this.matchedImageLoadFailed.update((m) => ({ ...m, [key]: true }));
              return;
            }
            this.service.downloadDocument(match.matchedCaseId, doc.id).subscribe({
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
