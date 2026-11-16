import { DestroyRef, afterNextRender } from '@angular/core';

/**
 * Keeps a folder strip readable at any width: stacks the tabs under the lead when the two do not
 * fit on one row, and marks a tab row that still overflows so its end fades out. Measured and not
 * a breakpoint: each screen has its own title and its own number of tabs.
 */
export function watchFolderFit(host: HTMLElement, destroyRef: DestroyRef): void {
  afterNextRender(() => {
    const strip = host.querySelector<HTMLElement>('.folder');
    if (!strip) return;
    const lead = strip.querySelector<HTMLElement>('.folder-lead-content');
    const list = strip.querySelector<HTMLElement>('.tablist');
    if (!list) return;

    const update = (): void => {
      const tabs = [...list.querySelectorAll<HTMLElement>('.tab')];
      const tabsWidth = tabs.reduce((sum, tab) => sum + tab.offsetWidth, 0);
      const leadWidth = lead?.offsetWidth ?? 0;
      // Room for the fills on both sides and a gap between the title and the first tab.
      const spare = strip.clientWidth - leadWidth - tabsWidth - 48;
      strip.classList.toggle('is-stacked', leadWidth > 0 && spare < 0);
      const overflowing = list.scrollWidth > list.clientWidth + 1;
      strip.classList.toggle('is-overflowing', overflowing);
      strip.classList.toggle(
        'is-scrolled-end',
        list.scrollLeft + list.clientWidth >= list.scrollWidth - 1,
      );
    };

    const observer = new ResizeObserver(update);
    observer.observe(strip);
    observer.observe(list);
    if (lead) observer.observe(lead);
    const mutations = new MutationObserver(update);
    mutations.observe(list, { childList: true, subtree: true, characterData: true });
    list.addEventListener('scroll', update, { passive: true });
    update();
    destroyRef.onDestroy(() => {
      observer.disconnect();
      mutations.disconnect();
      list.removeEventListener('scroll', update);
    });
  });
}
