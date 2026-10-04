/** Which side of the desk wrote it. Mirrors the backend's `StatusChangeActor`. */
export type MessageSender = 'INSURED' | 'ANALYST';

export interface CaseMessage {
  id: number;
  sender: MessageSender;
  /** Resolved by the backend, so the client needn't know the session's role to lay out the thread. */
  mine: boolean;
  body: string;
  createdAt: string;
  readAt: string | null;
}

export interface CaseMessageEvent {
  id: number;
  caseId: number;
  sender: MessageSender;
  body: string;
  createdAt: string;
}

export interface CaseMessageThread {
  messages: CaseMessage[];
  unread: number;
  canPost: boolean;
  /** Why not, already worded for whoever reads it. Null while the thread is open. */
  closedNotice: string | null;
  /** STOMP destination for this thread; the server builds it because the tenant is part of it. */
  topic: string;
  /** Which side the viewer is on, to place a pushed message. Null for a referente. */
  viewerSide: MessageSender | null;
}

/** The backend's cap (`CaseMessageRequest`), mirrored to warn before sending. */
export const MESSAGE_MAX_LENGTH = 2000;

/**
 * One row of `GET /cases/messages/inbox`: the case plus its last message. For the insured every own
 * case is a row, so the `lastMessage*` fields are null until someone writes.
 */
export interface CaseMessageInboxItem {
  caseId: number;
  /** Only for the insured: case ids repeat across insurers. */
  insurerSlug: string | null;
  insurerName: string | null;
  insuredName: string;
  analystName: string | null;
  branch: string;
  claimCause: string;
  status: string;
  lastMessageBody: string | null;
  lastMessageSender: MessageSender | null;
  lastMessageAt: string | null;
  unreadCount: number;
}

/** A case milestone shown between the messages, e.g. "Te pedimos documentación". */
export interface ChatEvent {
  at: string;
  label: string;
  tone?: 'warning';
  action?: { label: string; link: (string | number)[]; queryParams?: Record<string, string> };
}
