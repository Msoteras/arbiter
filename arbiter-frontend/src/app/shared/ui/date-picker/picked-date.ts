/** "mar 14 oct 2026": short enough for a field, with the weekday that helps recall the day. */
export function formatPickedDate(isoDate: string): string {
  return new Date(`${isoDate}T00:00:00Z`)
    .toLocaleDateString('es-AR', {
      weekday: 'short',
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      timeZone: 'UTC',
    })
    .replace(/[,.]/g, '');
}

/** "14/10/2026", for a range, where two long dates would not fit the field. */
export function formatShortDate(isoDate: string): string {
  const [year, month, day] = isoDate.split('-');
  return `${day}/${month}/${year}`;
}
