import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { FilePreviewComponent } from './file-preview.component';

@Component({
  imports: [FilePreviewComponent],
  template: `<app-file-preview [file]="file" />`,
})
class HostComponent {
  readonly file = new File(['%PDF-1.4'], 'denuncia.pdf', { type: 'application/pdf' });
}

describe('FilePreviewComponent', () => {
  let fixture: ComponentFixture<HostComponent>;

  beforeEach(() => {
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  it('the large view offers full screen', () => {
    const toggle = fixture.nativeElement.querySelector('.toggle') as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();
    const modal = document.querySelector('.modal') ?? fixture.nativeElement;
    expect(modal.textContent).toContain('Pantalla completa');
  });
});
