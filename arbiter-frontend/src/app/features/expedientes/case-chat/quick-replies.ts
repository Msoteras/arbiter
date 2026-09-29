export interface QuickReply {
  label: string;
  text: string;
}

/** Drafts the analyst edits before sending, never sent as-is. */
export function analystQuickReplies(insuredName: string, missingDocs: string[] = []): QuickReply[] {
  const firstName = insuredName.trim().split(/\s+/)[0] ?? '';
  const greeting = firstName ? `Hola ${firstName}` : 'Hola';
  const docs = missingDocs.length
    ? `: ${missingDocs.join(', ')}`
    : ' que te indicamos';
  return [
    {
      label: 'Solicitar documentación',
      text: `${greeting}, para avanzar con tu reclamo necesitamos la documentación${docs}. Podés subirla desde tu portal.`,
    },
    {
      label: 'Pedir aclaración',
      text: `${greeting}, necesitamos que nos aclares un punto de tu denuncia: `,
    },
    {
      label: 'Confirmar recepción',
      text: `${greeting}, recibimos lo que nos enviaste. Lo estamos revisando y te avisamos ante cualquier novedad.`,
    },
  ];
}
