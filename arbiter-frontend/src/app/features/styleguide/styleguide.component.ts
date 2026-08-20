import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';

import { ButtonComponent } from '../../shared/ui/button/button.component';
import { BadgeComponent } from '../../shared/ui/badge/badge.component';
import { InfoTipComponent } from '../../shared/ui/info-tip/info-tip.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { InputComponent } from '../../shared/ui/input/input.component';
import { TextareaComponent } from '../../shared/ui/textarea/textarea.component';
import { ModalComponent } from '../../shared/ui/modal/modal.component';
import { FraudGaugeComponent } from '../../shared/ui/fraud-gauge/fraud-gauge.component';
import { SeverityLabelComponent } from '../../shared/ui/severity-label/severity-label.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state/empty-state.component';
import { SelectComponent } from '../../shared/ui/select/select.component';
import { PaginationComponent } from '../../shared/ui/pagination/pagination.component';
import { TableComponent } from '../../shared/ui/table/table.component';
import { CheckboxComponent } from '../../shared/ui/checkbox/checkbox.component';
import { LogoComponent } from '../../shared/ui/logo/logo.component';
import { FilePreviewComponent } from '../../shared/ui/file-preview/file-preview.component';
import { StatusTimelineComponent } from '../../shared/ui/status-timeline/status-timeline.component';
import { MenuButtonComponent, MenuItem } from '../../shared/ui/menu-button/menu-button.component';
import { SpinnerComponent } from '../../shared/ui/spinner/spinner.component';
import { InlineLoadingComponent } from '../../shared/ui/inline-loading/inline-loading.component';
import { SaveBarComponent } from '../../shared/ui/save-bar/save-bar.component';
import { StatTileComponent } from '../../shared/ui/stat-tile/stat-tile.component';
import { ToastService } from '../../shared/ui/toast/toast.service';
import { StatusTransition } from '../../core/models/expediente';

interface Token {
  name: string;
  v: string;
}
interface TokenGroup {
  title: string;
  items: Token[];
}
interface Knob {
  name: string;
  v: string;
  default: string;
}
interface Swatch {
  name: string;
  hex: string;
  v: string;
}

/**
 * Vitrina viva del design system. Renderiza tokens y componentes reales (los que
 * corren en producción), así que es la fuente de verdad visual y detecta drift al
 * instante. Sin lógica de negocio: solo compone el kit.
 */
@Component({
  selector: 'app-styleguide',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonComponent,
    BadgeComponent,
    InfoTipComponent,
    CardComponent,
    InputComponent,
    TextareaComponent,
    ModalComponent,
    FraudGaugeComponent,
    SeverityLabelComponent,
    EmptyStateComponent,
    SelectComponent,
    PaginationComponent,
    TableComponent,
    CheckboxComponent,
    LogoComponent,
    FilePreviewComponent,
    StatusTimelineComponent,
    MenuButtonComponent,
    SpinnerComponent,
    InlineLoadingComponent,
    SaveBarComponent,
    StatTileComponent,
  ],
  template: `
    <div class="sg">
      <header class="sg-header">
        <h1 class="sg-h1">Arbiter · Design System</h1>
        <p class="sg-lead">
          Tokens y componentes reales de la app. Esta página se alimenta sola: consume el mismo
          kit que las pantallas, así que es la fuente de verdad visual del sistema.
        </p>
      </header>

      <!-- ================= TOKENS ================= -->
      <h2 class="sg-h2">Tokens</h2>

      <section class="sg-block">
        <h3 class="sg-h3">Paleta editable · tema en vivo</h3>
        <p class="t-note edit-note">
          Cambiá un color y toda la página se re-tematiza al instante: los componentes consumen
          tokens, no valores crudos. Es la misma perilla que se usaría para dark mode o branding por
          aseguradora.
          <button class="linkbtn" type="button" (click)="resetColors()">Restaurar</button>
        </p>
        <div class="knobs">
          @for (k of brandKnobs; track k.v) {
            <label class="knob">
              <input
                type="color"
                [value]="colors()[k.v]"
                (input)="setColor(k.v, $any($event.target).value)"
              />
              <span class="knob-info">
                <span class="tok-name">{{ k.name }}</span>
                <span class="tok-var mono">{{ k.v }} · {{ colors()[k.v] }}</span>
              </span>
            </label>
          }
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Accent · technicolor</h3>
        <p class="sg-p">Primitivos. No se consumen directo: se usan vía los roles de <span class="mono">Estado / semáforo</span> (abajo).</p>
        <div class="swatches">
          @for (s of technicolor; track s.v) {
            <div class="swatch">
              <span class="chip" [style.background]="'var(' + s.v + ')'"></span>
              <span class="tok-name">{{ s.name }}</span>
              <span class="tok-var mono">{{ s.v }} · {{ s.hex }}</span>
            </div>
          }
        </div>
      </section>

      @for (group of colorGroups; track group.title) {
        <section class="sg-block">
          <h3 class="sg-h3">{{ group.title }}</h3>
          <div class="swatches">
            @for (t of group.items; track t.v) {
              <div class="swatch">
                <span class="chip" [style.background]="'var(' + t.v + ')'"></span>
                <span class="tok-name">{{ t.name }}</span>
                <span class="tok-var mono">{{ t.v }}</span>
              </div>
            }
          </div>
        </section>
      }

      <section class="sg-block">
        <h3 class="sg-h3">Tipografía · escala de tamaños</h3>
        @for (t of typeScale; track t.v) {
          <div class="type-row">
            <span [style.font-size]="'var(' + t.v + ')'">Arbiter · siniestro</span>
            <span class="tok-var mono">{{ t.name }} · {{ t.v }}</span>
          </div>
        }
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Tipografía · estilos de texto</h3>
        <div class="type-styles">
          <div class="ts-row"><span class="t-page-title">Título de página</span><span class="tok-var mono">.t-page-title</span></div>
          <div class="ts-row"><span class="t-section-label">Etiqueta de sección</span><span class="tok-var mono">.t-section-label</span></div>
          <div class="ts-row"><span class="t-field-label">Etiqueta de campo</span><span class="tok-var mono">.t-field-label</span></div>
          <div class="ts-row"><span class="t-body">Texto de cuerpo por defecto.</span><span class="tok-var mono">.t-body</span></div>
          <div class="ts-row"><span class="t-note">Nota al pie / caption tenue.</span><span class="tok-var mono">.t-note</span></div>
          <div class="ts-row"><span class="t-body mono">EXP-2024-000123</span><span class="tok-var mono">.mono</span></div>
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Pesos</h3>
        @for (w of weights; track w.v) {
          <div class="type-row">
            <span [style.font-weight]="'var(' + w.v + ')'">Arbiter · siniestro</span>
            <span class="tok-var mono">{{ w.name }} · {{ w.v }}</span>
          </div>
        }
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Espaciado</h3>
        @for (t of spaceScale; track t.v) {
          <div class="space-row">
            <span class="space-bar" [style.width]="'var(' + t.v + ')'"></span>
            <span class="tok-var mono">{{ t.name }} · {{ t.v }}</span>
          </div>
        }
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Radios</h3>
        <div class="radii">
          @for (t of radii; track t.v) {
            <div class="radius-item">
              <span class="radius-box" [style.border-radius]="'var(' + t.v + ')'"></span>
              <span class="tok-var mono">{{ t.name }} · {{ t.v }}</span>
            </div>
          }
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Sombra y overlay</h3>
        <div class="row">
          <div class="shadow-demo">
            <span class="tok-var mono">--shadow-modal</span>
          </div>
          <div class="overlay-demo">
            <span class="tok-var mono">--overlay-backdrop</span>
          </div>
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Íconos · convenciones</h3>
        <div class="row">
          <span class="icon-conv"><span class="glyph">✦</span> Sugerencia del modelo (IA)</span>
          <span class="icon-conv"><span class="glyph">▲</span> Severidad / riesgo (nunca por color)</span>
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Logo</h3>
        <p class="sg-p">
          Isotipo (<span class="mono">app-logo</span>): hereda <span class="mono">currentColor</span>
          y escala al ancho del host. Usado en el login y la shell.
        </p>
        <div class="row">
          <app-logo class="sg-logo-sm" />
          <app-logo class="sg-logo-md" />
          <span class="sg-logo-dark"><app-logo class="sg-logo-md" /></span>
        </div>
      </section>

      <!-- ================= COMPONENTES ================= -->
      <h2 class="sg-h2">Componentes</h2>

      <section class="sg-block">
        <h3 class="sg-h3">Button</h3>
        <div class="row">
          <app-button variant="primary">Primary</app-button>
          <app-button variant="secondary">Secondary</app-button>
          <app-button variant="accent">Accent</app-button>
          <app-button variant="primary" [disabled]="true">Disabled</app-button>
          <app-button variant="primary" [loading]="true">Loading</app-button>
          <app-button variant="secondary" [loading]="true">Loading</app-button>
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Badge</h3>
        <div class="row">
          <app-badge variant="solid">Estado técnico</app-badge>
          <app-badge variant="strong">Estado final</app-badge>
          <app-badge variant="dashed">Sin datos</app-badge>
        </div>
        <p class="sg-p">Con punto de semáforo (<span class="mono">tone</span>): solo el punto lleva color, el texto sigue neutro.</p>
        <div class="row">
          <app-badge tone="ok">Aprobado</app-badge>
          <app-badge tone="warning">Falta documentación</app-badge>
          <app-badge tone="danger">Rechazado</app-badge>
          <app-badge tone="info">En revisión</app-badge>
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Info tip</h3>
        <p class="sg-p">
          Aclaración puntual al lado de un label — un solo dato, no un bloque entero (para eso
          está <span class="mono">.section-hint</span>). Toggle por click, no hover: funciona
          igual en mobile y con teclado.
        </p>
        <div class="row">
          <span class="t-field-label">
            Carencia (días)
            <app-info-tip>
              Durante la carencia la cobertura todavía no aplica, aunque la póliza esté vigente:
              un siniestro dentro de ese plazo no se cubre.
            </app-info-tip>
          </span>
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Toast</h3>
        <p class="sg-p">
          Aviso efímero, montado una sola vez en <span class="mono">app.html</span> y disparado
          desde cualquier pantalla vía <span class="mono">ToastService.show()</span>. Reemplaza el
          texto de error fijo al lado de un botón de guardar (ej. "El backend rechazó el
          guardado") — el mensaje no debe quedar clavado en la pantalla, así que se muestra abajo
          a la derecha y se cierra solo.
        </p>
        <div class="row">
          <app-button variant="secondary" (click)="toastService.show('No se pudo guardar: el backend no respondió.', 'danger')">
            Disparar error
          </app-button>
          <app-button variant="secondary" (click)="toastService.show('Cambios guardados en el motor.', 'ok')">
            Disparar éxito
          </app-button>
          <app-button variant="secondary" (click)="toastService.show('rules-service tardó en responder — reintentando.', 'warning')">
            Disparar warning
          </app-button>
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Card</h3>
        <div class="row cards">
          <app-card heading="Resumen del siniestro">
            <p class="sg-p">Contenido de la tarjeta.</p>
          </app-card>
          <app-card variant="soft" heading="Fondo tenue">
            <p class="sg-p">Variante <span class="mono">soft</span>.</p>
          </app-card>
          <app-card variant="ai" icon="✦" heading="Recomendación del modelo">
            <p class="sg-p">Variante <span class="mono">ai</span>: lavado teal para sugerencias del modelo.</p>
          </app-card>
          <app-card [flush]="true" heading="Sin padding">
            <p class="sg-p">Variante <span class="mono">flush</span>, para contenido que llega al borde (ej. una tabla).</p>
          </app-card>
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Input / Textarea</h3>
        <div class="col narrow">
          <app-input [(value)]="sampleInput" placeholder="N° de expediente" />
          <app-input type="password" [(value)]="samplePassword" placeholder="Contraseña" />
          <app-input type="date" [(value)]="sampleDate" />
          <app-textarea [(value)]="sampleArea" placeholder="Justificación…" [rows]="3" />
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Select</h3>
        <p class="sg-p">
          Mismo tratamiento visual que <span class="mono">app-input</span>. Se usa en filtros de
          listados (ej. bandeja de expedientes: estado, tipo de siniestro).
        </p>
        <div class="col narrow">
          <app-select
            [(value)]="sampleSelect"
            [options]="sampleSelectOptions"
            placeholder="Todos los estados"
          />
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Menu button</h3>
        <p class="sg-p">
          Botón con menú desplegable (ej. "Exportar" en la bandeja de expedientes: CSV / XLSX).
          Mismo trigger que <span class="mono">app-button</span>; el panel usa el tratamiento
          visual de <span class="mono">app-select</span>. Acepta el mismo
          <span class="mono">size</span> que el botón: <span class="mono">sm</span> para triggers
          que viven dentro de una fila de tabla (ej. "Reasignar" en la columna Analista).
        </p>
        <div class="row">
          <app-menu-button [items]="sampleMenuItems" (itemSelected)="onSampleMenuSelect($event)">
            Exportar
          </app-menu-button>
          <app-menu-button
            [items]="sampleMenuItems"
            size="sm"
            (itemSelected)="onSampleMenuSelect($event)"
          >
            Exportar (sm)
          </app-menu-button>
          @if (sampleMenuChoice(); as choice) {
            <span class="t-note">Elegiste: <span class="mono">{{ choice }}</span></span>
          }
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Pagination</h3>
        <p class="sg-p">
          Pager para listados paginados server-side (Spring Data, <span class="mono">page</span>
          0-based). Sin conocimiento del dominio — cualquier listado paginado lo reutiliza.
        </p>
        <app-pagination
          [page]="samplePage()"
          [totalPages]="7"
          [totalElements]="132"
          [size]="20"
          (pageChange)="samplePage.set($event)"
        />
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Modal</h3>
        <div class="row">
          <app-button (click)="modalOpen.set(true)">Abrir modal</app-button>
          <app-button variant="secondary" (click)="sidePanelOpen.set(true)">Abrir panel lateral</app-button>
        </div>
        <p class="sg-p">Variante <span class="mono">side</span>: panel deslizante desde el borde, para formularios tipo "alta de X" sobre un listado.</p>
        <app-modal
          [open]="modalOpen()"
          heading="Justificar decisión"
          (close)="modalOpen.set(false)"
        >
          <p class="sg-p">Toda decisión del analista queda justificada y auditada.</p>
          <app-textarea placeholder="Justificación (obligatoria)…" [rows]="3" />
          <ng-container modalActions>
            <app-button variant="secondary" (click)="modalOpen.set(false)">Cancelar</app-button>
            <app-button (click)="modalOpen.set(false)">Confirmar</app-button>
          </ng-container>
        </app-modal>
        <app-modal
          [open]="sidePanelOpen()"
          heading="Nuevo usuario"
          variant="side"
          (close)="sidePanelOpen.set(false)"
        >
          <p class="sg-p">Contenido del panel, igual que un modal centrado.</p>
          <ng-container modalActions>
            <app-button variant="secondary" (click)="sidePanelOpen.set(false)">Cancelar</app-button>
            <app-button (click)="sidePanelOpen.set(false)">Confirmar</app-button>
          </ng-container>
        </app-modal>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Fraud gauge</h3>
        <div class="row">
          <app-fraud-gauge [band]="1" />
          <app-fraud-gauge [band]="2" />
          <app-fraud-gauge [band]="3" />
          <app-fraud-gauge [band]="4" />
          <app-fraud-gauge [band]="null" />
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Severity label</h3>
        <div class="row">
          <app-severity-label level="bajo" />
          <app-severity-label level="medio" />
          <app-severity-label level="alto" />
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Empty state</h3>
        <div class="narrow">
          <app-empty-state message="Listado de expedientes" />
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Logo</h3>
        <p class="sg-p">
          El símbolo va en <span class="mono">currentColor</span>: una sola versión sirve para
          fondo claro y oscuro. El tamaño se controla con <span class="mono">--logo-size</span>
          y el texto escala con él. Los archivos originales del export están en
          <span class="mono">public/brand/</span>.
        </p>
        <div class="row logos">
          <app-logo />
          <app-logo variant="symbol" />
          <span class="logo-on-dark"><app-logo /></span>
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Table</h3>
        <p class="sg-p">
          Solo aporta el look (header tenue en mayúsculas, filas separadas por borde). El
          contenido se proyecta como <span class="mono">thead</span>/<span class="mono">tbody</span>
          nativos. Va dentro de <span class="mono">app-card [flush]</span> para que llegue al borde.
        </p>
        <p class="sg-p">
          <span class="mono">[fixed]="true"</span> reparte el ancho por columna en vez de por
          contenido: las que no declaran ancho quedan todas iguales. Para una matriz (ej. la agenda
          documental) es lo que corresponde — por contenido, cada columna mide lo que mide su
          título y la grilla se ve torcida.
        </p>
        <app-card [flush]="true">
          <app-table>
            <thead>
              <tr><th>N°</th><th>Estado</th><th>Asegurado</th></tr>
            </thead>
            <tbody>
              <tr><td class="mono">#1</td><td>Pendiente de revisión</td><td>Martina Soteras</td></tr>
              <tr><td class="mono">#2</td><td>Falta documentación</td><td>Julián Pérez</td></tr>
            </tbody>
          </app-table>
        </app-card>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Checkbox</h3>
        <p class="sg-p">
          Conserva el <span class="mono">&lt;input&gt;</span> nativo (foco por teclado y lectores
          de pantalla salen gratis); el componente aporta tamaño, área de toque de 40px y la
          etiqueta clickeable.
        </p>
        <div class="col narrow">
          <app-checkbox [(checked)]="sampleCheckbox">
            ¿Sos Persona Políticamente Expuesta (PEP)?
          </app-checkbox>
          <app-checkbox [checked]="true" [disabled]="true">Deshabilitado</app-checkbox>
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Status timeline</h3>
        <p class="sg-p">
          Historial de estados del expediente. El último recibe el tono del estado actual.
        </p>
        <app-card>
          <app-status-timeline [history]="sampleHistory" currentStatus="PENDING_ANALYST_REVIEW" />
        </app-card>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">File preview</h3>
        <p class="sg-p">
          Vista previa de un archivo elegido <em>antes</em> de subirlo — el
          <span class="mono">File</span> ya está en memoria, no hay llamada al backend. Miniatura
          + click para agrandar en línea. Elegí un JPG, PNG o PDF para probarlo:
        </p>
        <div class="col narrow">
          <label class="sg-file-btn">
            Elegir archivo
            <input type="file" accept="image/*,.pdf" hidden (change)="onSampleFile($event)" />
          </label>
          @if (sampleFile(); as f) {
            <app-file-preview [file]="f" />
          }
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Doc upload</h3>
        <p class="sg-p">
          Componente compuesto: los 4 slots de documentación de un expediente, con validación
          (JPG/PNG/PDF, 10 MB), drag &amp; drop y vista previa de cada archivo. Al enviar hace
          <span class="mono">POST /cases/&#123;id&#125;/documents</span>, que en el backend
          <strong>re-dispara la clasificación</strong>.
        </p>
        <p class="sg-p">
          No se muestra vivo acá a propósito: necesita un <span class="mono">caseId</span> real y
          cualquier envío modificaría un expediente. Se ve en el detalle del expediente (cuando
          falta documentación) y en el portal del asegurado.
        </p>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Spinner</h3>
        <p class="sg-p">
          El símbolo de Arbiter girando, sin texto. El trazo va en
          <span class="mono">currentColor</span>, así que hereda el color de donde se lo ponga —
          blanco sobre un botón oscuro, tinta sobre papel.
        </p>
        <div class="row">
          <app-spinner [size]="16" />
          <app-spinner [size]="24" />
          <app-spinner [size]="48" />
        </div>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Save bar</h3>
        <p class="sg-p">
          Pie de una sección editable: estado a la izquierda, descartar y guardar a la derecha.
          <span class="mono">dirty</span> gobierna todo — sin cambios no hay nada que guardar ni que
          descartar, y los dos botones se apagan. Así el botón dice la verdad sobre si queda trabajo
          por persistir, en vez de estar siempre habilitado.
        </p>
        <p class="sg-p">
          <span class="mono">canSave</span> es la validación propia de la sección (un número fuera
          de rango, por ejemplo). Va aparte de <span class="mono">dirty</span> para que un botón
          deshabilitado signifique una sola cosa por vez.
        </p>
        <app-save-bar [dirty]="false" (save)="noop()" (discard)="noop()" />
        <app-save-bar [dirty]="true" (save)="noop()" (discard)="noop()" />
        <app-save-bar [dirty]="true" [saving]="true" (save)="noop()" (discard)="noop()" />
        <app-save-bar [dirty]="true" error="No se pudo guardar: el motor no respondió" (save)="noop()" (discard)="noop()" />
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Inline loading</h3>
        <p class="sg-p">
          Carga "en el lugar": spinner + mensaje opcional, dentro de una pantalla que ya tiene su
          shell visible. Es la contraparte liviana de <span class="mono">app-loading</span>.
        </p>
        <app-inline-loading message="Cargando expedientes…" />
        <app-inline-loading />
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Loading (pantalla completa)</h3>
        <p class="sg-p">
          Overlay a viewport completo con el spinner de marca y un mensaje. Se reserva al arranque
          (login → primer home), cuando todavía no hay shell que mostrar.
        </p>
        <p class="sg-p">
          No se muestra vivo acá porque taparía la vitrina entera. Para verlo, cerrá sesión y volvé
          a entrar.
        </p>
      </section>

      <section class="sg-block">
        <h3 class="sg-h3">Stat tile</h3>
        <p class="sg-p">
          La tarjeta de métrica de las pantallas de inicio. <span class="mono">tone</span> se usa
          solo cuando el número comunica algo: <span class="mono">accent</span> para el dato propio
          a resaltar, <span class="mono">danger</span> para una alerta. Con
          <span class="mono">loading</span> muestra un guion en vez de un 0 que después salta a su
          valor real y se lee como "no hay nada".
        </p>
        <div class="row cards">
          <app-stat-tile [value]="14" label="Total expedientes" sub="en la aseguradora" />
          <app-stat-tile [value]="5" tone="accent" label="Pendientes" sub="asignados a vos" />
          <app-stat-tile [value]="3" tone="danger" label="Riesgo alto" sub="requieren atención" />
          <app-stat-tile [loading]="true" label="Resueltos" sub="en total" />
        </div>
      </section>
    </div>
  `,
  styles: `
    :host { display: block; }
    .sg { padding: var(--space-5) var(--space-5) var(--space-7); max-width: 900px; }
    .sg-header { margin-bottom: var(--space-6); }
    .sg-h1 { margin: 0 0 var(--space-2); font-size: var(--font-size-xl); font-weight: var(--font-weight-medium); }
    .sg-lead { margin: 0; font-size: var(--font-size-body); color: var(--text-tertiary); max-width: 620px; }
    .sg-h2 {
      margin: var(--space-6) 0 var(--space-4);
      padding-bottom: var(--space-2);
      border-bottom: 1px solid var(--border-default);
      font-size: var(--font-size-lg);
      font-weight: var(--font-weight-medium);
    }
    .sg-h3 {
      margin: 0 0 var(--space-3);
      font-size: var(--font-size-2xs);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-tertiary);
      font-weight: var(--font-weight-medium);
    }
    .sg-block { margin-bottom: var(--space-5); }
    .sg-p { margin: 0; font-size: var(--font-size-body); color: var(--text-secondary); }

    .row { display: flex; gap: var(--space-3); flex-wrap: wrap; align-items: center; }
    .row.cards { align-items: stretch; }
    .row.logos { gap: var(--space-6); }

    /* Para verificar que el logo se banca fondo oscuro sin cargar la variante -white. */
    .logo-on-dark {
      display: inline-flex;
      padding: var(--space-3) var(--space-4);
      border-radius: var(--radius-ctl);
      background: var(--accent);
      color: var(--text-on-emphasis);
    }

    /* Mismo lenguaje que el label de archivo de app-doc-upload. */
    .sg-file-btn {
      align-self: flex-start;
      font-size: var(--font-size-sm);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      padding: var(--space-2) var(--space-3);
      cursor: pointer;
    }
    .sg-file-btn:hover { background: var(--surface-sunken); }
    .cards app-card { flex: 1 1 240px; }
    .col { display: flex; flex-direction: column; gap: var(--space-3); }
    .narrow { max-width: 340px; }

    /* Tokens de color */
    .swatches { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: var(--space-3); }
    .swatch { display: flex; flex-direction: column; gap: 2px; }
    .chip { height: 40px; border-radius: var(--radius-ctl); border: 1px solid var(--border-default); }
    .tok-name { font-size: var(--font-size-sm); color: var(--text-primary); }
    .tok-var { font-size: var(--font-size-xs); color: var(--text-muted); }

    /* Paleta editable */
    .edit-note { max-width: 620px; margin: 0 0 var(--space-3); }
    .linkbtn { border: none; background: none; padding: 0 0 0 var(--space-1); font: inherit; font-size: var(--font-size-xs); color: var(--text-primary); text-decoration: underline; cursor: pointer; }
    .knobs { display: flex; flex-wrap: wrap; gap: var(--space-4); }
    .knob { display: flex; align-items: center; gap: var(--space-2); cursor: pointer; }
    .knob input[type='color'] { width: 40px; height: 40px; padding: 0; border: 1px solid var(--border-default); border-radius: var(--radius-ctl); background: none; cursor: pointer; }
    .knob-info { display: flex; flex-direction: column; gap: 1px; }

    /* Tipografía */
    .type-row { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-4); padding: var(--space-2) 0; border-bottom: 1px solid var(--border-subtle); }
    .type-styles { display: flex; flex-direction: column; }
    .ts-row { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-4); padding: var(--space-2) 0; border-bottom: 1px solid var(--border-subtle); }

    /* Sombra / overlay */
    .shadow-demo { width: 160px; height: 72px; display: flex; align-items: center; justify-content: center; background: var(--surface); border-radius: var(--radius-modal); box-shadow: var(--shadow-modal); }
    .overlay-demo { width: 160px; height: 72px; display: flex; align-items: center; justify-content: center; background: var(--overlay-backdrop); border-radius: var(--radius-ctl); color: var(--text-on-emphasis); }

    /* Íconos */
    .icon-conv { display: inline-flex; align-items: center; gap: var(--space-2); font-size: var(--font-size-body); color: var(--text-secondary); }
    .icon-conv .glyph { color: var(--text-primary); }

    .sg-logo-sm { width: 28px; color: var(--text-primary); }
    .sg-logo-md { width: 56px; color: var(--text-primary); }
    .sg-logo-dark { display: inline-flex; padding: var(--space-3); border-radius: var(--radius-card); background: var(--brand-panel-bg); color: var(--text-on-emphasis); }

    /* Espaciado */
    .space-row { display: flex; align-items: center; gap: var(--space-3); padding: 3px 0; }
    .space-bar { display: block; height: 14px; background: var(--action-primary-bg); border-radius: 2px; }

    /* Radios */
    .radii { display: flex; gap: var(--space-4); flex-wrap: wrap; }
    .radius-item { display: flex; flex-direction: column; gap: var(--space-2); align-items: flex-start; }
    .radius-box { display: block; width: 56px; height: 56px; background: var(--surface-head); border: 1px solid var(--border-strong); }

    .mono { font-family: var(--font-mono); }
  `,
})
export class StyleguideComponent {
  private readonly doc = inject(DOCUMENT);
  protected readonly toastService = inject(ToastService);

  protected readonly modalOpen = signal(false);
  protected readonly sidePanelOpen = signal(false);
  protected readonly sampleInput = signal('');
  protected readonly samplePassword = signal('');
  protected readonly sampleDate = signal('');
  protected readonly sampleArea = signal('');
  protected readonly sampleSelect = signal('');
  protected readonly sampleSelectOptions = [
    { value: 'PENDING_ANALYST_REVIEW', label: 'Pendiente de revisión' },
    { value: 'AWAITING_DOCUMENTATION', label: 'Falta documentación' },
    { value: 'APPROVED', label: 'Aprobado' },
  ];
  protected readonly samplePage = signal(2);
  protected readonly sampleCheckbox = signal(false);
  protected readonly sampleMenuItems: MenuItem[] = [
    { value: 'csv', label: 'CSV (.csv)' },
    { value: 'xlsx', label: 'Excel (.xlsx)' },
  ];
  protected readonly sampleMenuChoice = signal('');

  /** La vitrina no guarda nada: los botones existen para mostrar los estados, no para hacer algo. */
  protected noop(): void {}

  protected onSampleMenuSelect(value: string): void {
    this.sampleMenuChoice.set(value);
  }

  /** El archivo lo elige quien mira la página: no hay forma honesta de fabricar un File de ejemplo. */
  protected readonly sampleFile = signal<File | null>(null);

  protected readonly sampleHistory: StatusTransition[] = [
    {
      fromStatus: null,
      toStatus: 'PENDING_CLASSIFICATION',
      actor: 'INSURED',
      reason: 'denuncia registrada',
      changedAt: '2026-06-29T00:27:10Z',
    },
    {
      fromStatus: 'PENDING_CLASSIFICATION',
      toStatus: 'PENDING_ANALYST_REVIEW',
      actor: 'SYSTEM',
      reason: 'clasificación: PROCEDENTE',
      changedAt: '2026-06-29T00:27:41Z',
    },
  ];

  protected onSampleFile(event: Event): void {
    this.sampleFile.set((event.target as HTMLInputElement).files?.[0] ?? null);
  }

  /** Perillas de tema editables en vivo. Escriben sobre :root → re-tematizan todo. */
  protected readonly brandKnobs: Knob[] = [
    { name: 'Acento (estados activos)', v: '--accent', default: '#00a99d' },
    { name: 'Texto (ink)', v: '--c-ink', default: '#191c1f' },
    { name: 'Fondo', v: '--c-bg', default: '#ffffff' },
    { name: 'Fondo tenue', v: '--c-bg-soft', default: '#fbfaf7' },
    { name: 'Borde', v: '--c-border', default: '#e5e4dd' },
  ];

  protected readonly colors = signal<Record<string, string>>(
    Object.fromEntries(this.brandKnobs.map((k) => [k.v, k.default])),
  );

  protected setColor(v: string, value: string): void {
    this.doc.documentElement.style.setProperty(v, value);
    this.colors.update((c) => ({ ...c, [v]: value }));
  }

  protected resetColors(): void {
    for (const k of this.brandKnobs) {
      this.doc.documentElement.style.removeProperty(k.v);
    }
    this.colors.set(Object.fromEntries(this.brandKnobs.map((k) => [k.v, k.default])));
  }

  protected readonly technicolor: Swatch[] = [
    { name: 'Verde', hex: '#00A99D', v: '--accent-green' },
    { name: 'Amarillo', hex: '#F2B705', v: '--accent-yellow' },
    { name: 'Naranja', hex: '#E8632A', v: '--accent-orange' },
    { name: 'Rojo', hex: '#E63329', v: '--accent-red' },
    { name: 'Azul', hex: '#2E4A9E', v: '--accent-blue' },
  ];

  protected readonly weights: Token[] = [
    { name: 'regular', v: '--font-weight-regular' },
    { name: 'medium', v: '--font-weight-medium' },
    { name: 'bold', v: '--font-weight-bold' },
  ];

  protected readonly colorGroups: TokenGroup[] = [
    {
      title: 'Texto',
      items: [
        { name: 'text-primary', v: '--text-primary' },
        { name: 'text-secondary', v: '--text-secondary' },
        { name: 'text-tertiary', v: '--text-tertiary' },
        { name: 'text-muted', v: '--text-muted' },
      ],
    },
    {
      title: 'Superficies',
      items: [
        { name: 'surface', v: '--surface' },
        { name: 'surface-soft', v: '--surface-soft' },
        { name: 'surface-sunken', v: '--surface-sunken' },
        { name: 'surface-head', v: '--surface-head' },
      ],
    },
    {
      title: 'Bordes',
      items: [
        { name: 'border-subtle', v: '--border-subtle' },
        { name: 'border-default', v: '--border-default' },
        { name: 'border-control', v: '--border-control' },
        { name: 'border-strong', v: '--border-strong' },
      ],
    },
    {
      title: 'Acción',
      items: [
        { name: 'action-primary-bg', v: '--action-primary-bg' },
        { name: 'action-primary-bg-hover', v: '--action-primary-bg-hover' },
      ],
    },
    {
      title: 'Estado / semáforo',
      items: [
        { name: 'status-ok', v: '--status-ok' },
        { name: 'status-warning', v: '--status-warning' },
        { name: 'status-risk', v: '--status-risk' },
        { name: 'status-danger', v: '--status-danger' },
        { name: 'status-info', v: '--status-info' },
      ],
    },
  ];

  protected readonly typeScale: Token[] = [
    { name: 'xl', v: '--font-size-xl' },
    { name: 'lg', v: '--font-size-lg' },
    { name: 'md', v: '--font-size-md' },
    { name: 'body', v: '--font-size-body' },
    { name: 'sm', v: '--font-size-sm' },
    { name: 'xs', v: '--font-size-xs' },
    { name: '2xs', v: '--font-size-2xs' },
  ];

  protected readonly spaceScale: Token[] = [
    { name: 'space-1', v: '--space-1' },
    { name: 'space-2', v: '--space-2' },
    { name: 'space-3', v: '--space-3' },
    { name: 'space-4', v: '--space-4' },
    { name: 'space-5', v: '--space-5' },
    { name: 'space-6', v: '--space-6' },
    { name: 'space-7', v: '--space-7' },
  ];

  protected readonly radii: Token[] = [
    { name: 'ctl', v: '--radius-ctl' },
    { name: 'card', v: '--radius-card' },
    { name: 'modal', v: '--radius-modal' },
    { name: 'pill', v: '--radius-pill' },
  ];
}
