import { ChangeDetectionStrategy, Component, model } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ModalComponent } from '../../../../shared/ui/modal/modal.component';

interface Faq {
  question: string;
  answer: string;
  link?: { label: string; route: string };
}

// Every answer describes what the portal already does: limits, routes and wording come from the
// screens they point to (new-claim, doc-upload, case-tracking, profile, insured-status) and from
// cases-service: CaseNotificationService (which statuses email) and LapseSweepScheduler (18 months).
const FAQS: readonly Faq[] = [
  {
    question: '¿Cómo hago una denuncia?',
    answer: 'Tocá "Nueva denuncia" en el inicio. Son 3 pasos: datos, descripción y documentación.',
  },
  {
    question: '¿Qué archivos puedo subir?',
    answer: 'Fotos JPG o PNG y archivos PDF, de hasta 10 MB cada uno.',
  },
  {
    question: '¿Dónde veo cómo va mi siniestro?',
    answer:
      'En "Mis siniestros", entrá al siniestro. Te mandamos un mail cuando recibimos la denuncia, cuando te pedimos documentación y cuando se cierra.',
    link: { label: 'Ir a Mis siniestros', route: '/portal' },
  },
  {
    question: 'Me pidieron documentación. ¿Qué hago?',
    answer:
      'Entrá al siniestro y tocá "Cargar documentación". Con los documentos, revisamos tu caso de nuevo. Si pasan 18 meses desde la denuncia sin esa documentación, el siniestro caduca.',
  },
  {
    question: '¿Qué significa que mi siniestro está con un perito?',
    answer: 'Un perito verifica lo que pasó. Es posible que te contacte para coordinar.',
  },
  {
    question: '¿Cómo hago una consulta sobre mi siniestro?',
    answer:
      'Escribinos desde "Mensajes" o desde el seguimiento del siniestro. Te responde el equipo de siniestros de tu aseguradora.',
    link: { label: 'Ir a Mensajes', route: '/portal/messages' },
  },
  {
    question: '¿Cómo cambio mi email o mi teléfono?',
    answer:
      'En "Mi perfil", desde el menú con tus iniciales, arriba a la derecha. Tu nombre y tu DNI los informa tu aseguradora: si hay un error, contactate con ella.',
    link: { label: 'Ir a Mi perfil', route: '/portal/profile' },
  },
  {
    question: '¿Dónde veo mis pólizas?',
    answer: 'En "Mis pólizas", desde el menú con tus iniciales, arriba a la derecha.',
    link: { label: 'Ir a Mis pólizas', route: '/portal/policies' },
  },
];

@Component({
  selector: 'app-insured-help',
  imports: [RouterLink, ModalComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-modal [open]="open()" heading="Ayuda" [hideActions]="true" (close)="open.set(false)">
      <ul class="faqs">
        @for (faq of faqs; track faq.question) {
          <li>
            <details class="faq">
              <summary class="question">
                <span>{{ faq.question }}</span>
                <svg class="chevron" width="16" height="16" viewBox="0 0 20 20" aria-hidden="true">
                  <path
                    d="M6 8l4 4 4-4"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </summary>
              <div class="answer">
                <p class="t-body">{{ faq.answer }}</p>
                @if (faq.link; as l) {
                  <a class="answer-link" [routerLink]="l.route" (click)="open.set(false)"
                    >{{ l.label }} ›</a
                  >
                }
              </div>
            </details>
          </li>
        }
      </ul>
    </app-modal>
  `,
  styles: `
    @use 'media';

    .faqs {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .faqs li + li {
      border-top: 1px solid var(--border-subtle);
    }
    .question {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
      padding: var(--space-3) 0;
      font-size: var(--font-size-body);
      font-weight: var(--font-weight-medium);
      color: var(--text-primary);
      cursor: pointer;
      list-style: none;
      border-radius: var(--radius-ctl);
    }
    .question::-webkit-details-marker {
      display: none;
    }
    .question:hover {
      color: var(--accent-fg);
    }
    .question:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
    }
    .chevron {
      flex-shrink: 0;
      color: var(--text-muted);
      transition: transform 0.15s;
    }
    .faq[open] .chevron {
      transform: rotate(180deg);
    }
    .answer {
      padding-bottom: var(--space-4);
    }
    .answer p {
      margin: 0;
      color: var(--text-secondary);
      text-wrap: pretty;
    }
    .answer-link {
      display: inline-block;
      margin-top: var(--space-2);
      font-size: var(--font-size-body);
      color: var(--accent-fg);
      text-decoration: none;
    }
    .answer-link:hover {
      text-decoration: underline;
    }

    @include media.touch {
      .question {
        min-height: var(--touch-target);
      }
      .answer-link {
        display: inline-flex;
        align-items: center;
        min-height: var(--touch-target);
      }
    }
  `,
})
export class InsuredHelpComponent {
  readonly open = model(false);

  protected readonly faqs = FAQS;
}
