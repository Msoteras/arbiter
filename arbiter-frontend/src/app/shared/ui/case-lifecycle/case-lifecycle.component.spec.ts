import { ComponentFixture, TestBed } from '@angular/core/testing';

import { StatusTransition } from '../../../core/models/case';
import { CaseLifecycleComponent, unroutedMoves } from './case-lifecycle.component';
import { SAMPLE_STATUS_MOVES } from './case-lifecycle.sample';

function move(fromStatus: string | null, toStatus: string): StatusTransition {
  return {
    fromStatus,
    toStatus,
    actor: 'SYSTEM',
    reason: '',
    observation: null,
    changedAt: '2026-11-01T10:00:00Z',
  };
}

describe('CaseLifecycleComponent', () => {
  let fixture: ComponentFixture<CaseLifecycleComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    fixture = TestBed.createComponent(CaseLifecycleComponent);
    fixture.componentRef.setInput('transitions', SAMPLE_STATUS_MOVES);
    el = fixture.nativeElement;
  });

  it('has a drawn route for every move of the machine', () => {
    expect(unroutedMoves(SAMPLE_STATUS_MOVES)).toEqual([]);
  });

  it('draws a two-way pair as a single line', () => {
    fixture.detectChanges();
    // 27 moves plus the report; the 10 pairs that go both ways share their line.
    expect(el.querySelectorAll('path.edge').length).toBe(28 - 10);
  });

  it('gives each status a single arrowhead', () => {
    fixture.detectChanges();
    expect(el.querySelectorAll('path.stub').length).toBe(12);
  });

  it('lets a status be picked in the reference', () => {
    fixture.detectChanges();
    const approved = el.querySelector<SVGGElement>('g.node[aria-label="Aprobado"]')!;
    approved.dispatchEvent(new MouseEvent('click'));
    fixture.detectChanges();
    expect(approved.getAttribute('aria-pressed')).toBe('true');
  });

  it('shows a case path, its repeats and its current status, without picking', () => {
    fixture.componentRef.setInput('history', [
      move(null, 'PENDING_CLASSIFICATION'),
      move('PENDING_CLASSIFICATION', 'PENDING_ANALYST_REVIEW'),
      move('PENDING_ANALYST_REVIEW', 'PENDING_ANALYST_REVIEW'),
      move('PENDING_ANALYST_REVIEW', 'PENDING_EXPERT_REPORT'),
      move('PENDING_EXPERT_REPORT', 'PENDING_ANALYST_REVIEW'),
    ]);
    fixture.componentRef.setInput('currentStatus', 'PENDING_ANALYST_REVIEW');
    fixture.detectChanges();

    expect(el.querySelector('app-chip-group')).toBeNull();
    expect(el.querySelectorAll('path.edge.path').length).toBe(3);
    expect(el.querySelector('.count text')?.textContent?.trim()).toBe('×2');
    expect(el.querySelector('g.node.selected text')?.textContent?.trim()).toBe(
      'Pendiente de revisión',
    );
    expect(el.querySelector('g.node[tabindex]')).toBeNull();
  });

  it('shows the count of each status and dims the empty ones', () => {
    fixture.componentRef.setInput('counts', { PENDING_ANALYST_REVIEW: 12, APPROVED: 30 });
    fixture.detectChanges();

    const pills = [...el.querySelectorAll('.count-pill text')].map((t) => t.textContent?.trim());
    expect(pills.length).toBe(12);
    expect(pills).toContain('30');
    expect(el.querySelectorAll('g.node.dim').length).toBe(10);
    expect(el.querySelector('app-chip-group')).toBeNull();
  });

  it('lets a status be picked on the counts, and a second click clears it', () => {
    fixture.componentRef.setInput('counts', { APPROVED: 30 });
    fixture.detectChanges();
    const approved = el.querySelector<SVGGElement>('g.node[aria-label="Aprobado: 30"]')!;

    approved.dispatchEvent(new MouseEvent('click'));
    fixture.detectChanges();
    expect(approved.getAttribute('aria-pressed')).toBe('true');
    expect(el.querySelectorAll('path.edge.out, path.edge.in').length).toBeGreaterThan(0);

    approved.dispatchEvent(new MouseEvent('click'));
    fixture.detectChanges();
    expect(approved.getAttribute('aria-pressed')).toBe('false');
  });
});
