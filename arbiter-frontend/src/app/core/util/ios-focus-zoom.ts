/**
 * iOS Safari zooms in when a form field under 16px takes focus. `maximum-scale=1` turns that off
 * without blocking pinch-zoom there; Android does honor it, hence iOS only.
 */
export function disableIosFocusZoom(): void {
  // iPadOS reports itself as a Mac; the touch points tell them apart.
  const isIos =
    /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  if (!isIos) {
    return;
  }
  const viewport = document.querySelector('meta[name="viewport"]');
  viewport?.setAttribute('content', 'width=device-width, initial-scale=1, maximum-scale=1');
}
