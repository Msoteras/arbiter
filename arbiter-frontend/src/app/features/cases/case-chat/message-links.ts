/** A piece of a chat message: plain text, or a case number to link to that case. */
export interface MessagePart {
  text: string;
  caseId: number | null;
}

/**
 * "#42", and "expediente 42" / "siniestro 42" with an optional "#", "N°" or "nro." before the
 * number. A bare number ("42") is not a case: it could be an amount, a date or a phone.
 */
const CASE_NUMBER =
  /(?:#|\b(?:expediente|siniestro)\s+(?:#\s*|n(?:ro|°|º)?\.?\s*)?)(\d{1,9})(?!\d)/giu;

/** Splits a message so its case numbers can be links. Joining every `text` gives the message back. */
export function messageParts(body: string): MessagePart[] {
  const parts: MessagePart[] = [];
  let last = 0;
  for (const match of body.matchAll(CASE_NUMBER)) {
    const start = match.index;
    if (start > last) {
      parts.push({ text: body.slice(last, start), caseId: null });
    }
    parts.push({ text: match[0], caseId: Number(match[1]) });
    last = start + match[0].length;
  }
  if (last < body.length) {
    parts.push({ text: body.slice(last), caseId: null });
  }
  return parts;
}
