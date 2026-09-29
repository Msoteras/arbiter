import { TestBed } from '@angular/core/testing';
import { convertToParamMap } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { BranchOption, BranchesService } from '../branches.service';
import { ReportFiltersStore } from './report-filters.store';

describe('ReportFiltersStore', () => {
  function setup(list: () => ReturnType<BranchesService['list']>): ReportFiltersStore {
    TestBed.configureTestingModule({
      providers: [ReportFiltersStore, { provide: BranchesService, useValue: { list } }],
    });
    return TestBed.inject(ReportFiltersStore);
  }

  it('restores the period from the link, and ignores a broken one', () => {
    const store = setup(() => of([]));

    store.hydrate(convertToParamMap({ from: '2026-08-01', to: '2026-08-31' }));
    expect([store.from(), store.to()]).toEqual(['2026-08-01', '2026-08-31']);

    // Inverted: the defaults stay rather than putting a broken period on screen.
    store.hydrate(convertToParamMap({ from: '2026-09-01', to: '2026-08-01' }));
    expect([store.from(), store.to()]).toEqual(['2026-08-01', '2026-08-31']);
  });

  /** Without a catalog the select must still show the request is filtered, not "Todos". */
  it('names the linked branch when the catalog could not be read', () => {
    const store = setup(() => throwError(() => new Error('rules-service down')));
    store.hydrate(convertToParamMap({ branchId: '2' }));

    expect(store.branchOptions()).toEqual([{ value: '2', label: 'Ramo 2' }]);

    store.notePreviewedBranch('Hogar');
    expect(store.branchOptions()).toEqual([{ value: '2', label: 'Hogar' }]);
  });

  it('drops a linked branch the catalog no longer has', () => {
    const catalog = new Subject<BranchOption[]>();
    const store = setup(() => catalog);
    store.hydrate(convertToParamMap({ branchId: '9' }));
    expect(store.branchId()).toBe(9);

    catalog.next([{ id: 1, name: 'Celulares' }]);

    expect(store.branchId()).toBeNull();
    expect(store.branchOptions()).toEqual([{ value: '1', label: 'Celulares' }]);
  });

  it('keeps a linked branch the catalog has', () => {
    const store = setup(() => of([{ id: 1, name: 'Celulares' }]));
    store.hydrate(convertToParamMap({ branchId: '1' }));

    expect(store.branchId()).toBe(1);
    expect(store.branchOptions()).toEqual([{ value: '1', label: 'Celulares' }]);
  });

  it('carries the tab filter in the URL but not to the other tab', () => {
    const store = setup(() => of([]));
    store.tabParams.set({ riskBand: 'CRITICAL' });

    expect(store.asQueryParams()).toEqual(jasmine.objectContaining({ riskBand: 'CRITICAL' }));
    expect(store.sharedQueryParams()['riskBand']).toBeUndefined();
  });

  it('leaves unset filters out of the URL instead of writing "undefined"', () => {
    const store = setup(() => of([]));
    store.tabParams.set({ claimCause: undefined });

    expect(Object.keys(store.asQueryParams())).toEqual(['from', 'to']);
  });
});
