import { ComponentFixture } from '@angular/core/testing';

/**
 * Media queries follow the viewport and Karma's window cannot be resized, so the fixture is moved
 * into an iframe of the target size: inside it the component sees a real phone viewport.
 */
export async function mountInViewport(
  fixture: ComponentFixture<unknown>,
  width: number,
  height: number,
): Promise<HTMLIFrameElement> {
  const frame = document.createElement('iframe');
  frame.style.cssText = `width:${width}px;height:${height}px;border:0`;
  document.body.appendChild(frame);
  const doc = frame.contentDocument!;

  const loads: Promise<unknown>[] = [];
  // Karma injects the global stylesheet into <body>, component styles go to <head>.
  document.querySelectorAll('style, link[rel="stylesheet"]').forEach((node) => {
    const clone = node.cloneNode(true) as HTMLElement;
    if (clone instanceof HTMLLinkElement) {
      // The property is absolute; the attribute would resolve against about:blank.
      clone.href = (node as HTMLLinkElement).href;
      loads.push(new Promise((done) => (clone.onload = clone.onerror = done)));
    }
    doc.head.appendChild(clone);
  });
  await Promise.all(loads);

  doc.body.style.margin = '0';
  doc.body.appendChild(fixture.nativeElement);
  return frame;
}
