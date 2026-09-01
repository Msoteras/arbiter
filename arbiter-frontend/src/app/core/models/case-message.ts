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
