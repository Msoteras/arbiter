import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';

import { CaseChatComponent } from './case-chat.component';
import { CaseMessagesService } from '../case-messages.service';
import { CaseMessagesSocketService } from '../case-messages-socket.service';
import {
  CaseMessage,
  CaseMessageEvent,
  CaseMessageThread,
} from '../../../core/models/case-message';

/** The socket frame is shared by both sides and carries no `mine`: the component must place it. */
describe('CaseChatComponent · messages over the socket', () => {
  let fixture: ComponentFixture<CaseChatComponent>;
  let pushed: Subject<CaseMessageEvent>;
  let posted: Subject<CaseMessage>;
  let markRead: jasmine.Spy;

  const thread: CaseMessageThread = {
    messages: [
      {
        id: 1,
        sender: 'ANALYST',
        mine: false,
        body: 'Necesitamos la factura.',
        createdAt: '2026-08-30T12:05:00Z',
        readAt: null,
      },
    ],
    unread: 0,
    canPost: true,
    closedNotice: null,
    topic: '/topic/cases.bbva.29',
    viewerSide: 'INSURED',
  };

  beforeEach(async () => {
    pushed = new Subject<CaseMessageEvent>();
    posted = new Subject<CaseMessage>();
    markRead = jasmine.createSpy('markRead').and.returnValue(of(void 0));

    await TestBed.configureTestingModule({
      imports: [CaseChatComponent],
      providers: [
        {
          provide: CaseMessagesService,
          useValue: {
            thread: () => of(thread),
            markRead,
            post: () => posted.asObservable(),
            recordLatest: () => undefined,
            clearUnread: () => undefined,
          },
        },
        { provide: CaseMessagesSocketService, useValue: { watch: () => pushed.asObservable() } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CaseChatComponent);
    fixture.componentRef.setInput('caseId', 29);
    fixture.componentRef.setInput('counterparty', 'Equipo de siniestros');
    fixture.detectChanges();
  });

  function bubbles(): { who: string; body: string; mine: boolean }[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.msg')).map((el) => {
      const node = el as HTMLElement;
      return {
        // The author is only announced to screen readers, as "Name:".
        who: node.querySelector('.sr-only')!.textContent!.trim().replace(/:$/, ''),
        body: node.querySelector('.body')!.textContent!.trim(),
        mine: node.classList.contains('mine'),
      };
    });
  }

  it('places a message from the other side on the other side', () => {
    pushed.next({
      id: 2,
      caseId: 29,
      sender: 'ANALYST',
      body: '¿La tenés?',
      createdAt: '2026-08-30T12:06:00Z',
    });
    fixture.detectChanges();

    expect(bubbles().length).toBe(2);
    expect(bubbles()[1]).toEqual({ who: 'Equipo de siniestros', body: '¿La tenés?', mine: false });
  });

  it('places a message from the viewer side as their own', () => {
    pushed.next({
      id: 3,
      caseId: 29,
      sender: 'INSURED',
      body: 'La subo hoy.',
      createdAt: '2026-08-30T12:07:00Z',
    });
    fixture.detectChanges();

    expect(bubbles()[1]).toEqual({ who: 'Vos', body: 'La subo hoy.', mine: true });
  });

  /** The sender also receives its own message over the socket. */
  it('ignores the echo of a message already in the thread', () => {
    pushed.next({
      id: 1,
      caseId: 29,
      sender: 'ANALYST',
      body: 'Necesitamos la factura.',
      createdAt: '2026-08-30T12:05:00Z',
    });
    fixture.detectChanges();

    expect(bubbles().length).toBe(1);
  });

  it('shows a sent message once when its echo arrives before the POST answers', () => {
    const chat = fixture.componentInstance as unknown as {
      draft: { set(value: string): void };
      send(): void;
    };
    chat.draft.set('Ya la subí.');
    chat.send();

    pushed.next({
      id: 6,
      caseId: 29,
      sender: 'INSURED',
      body: 'Ya la subí.',
      createdAt: '2026-08-30T12:10:00Z',
    });
    posted.next({
      id: 6,
      sender: 'INSURED',
      mine: true,
      body: 'Ya la subí.',
      createdAt: '2026-08-30T12:10:00Z',
      readAt: null,
    });
    fixture.detectChanges();

    expect(bubbles().filter((b) => b.body === 'Ya la subí.')).toEqual([
      { who: 'Vos', body: 'Ya la subí.', mine: true },
    ]);
  });

  it('marks incoming messages as read, not outgoing ones', () => {
    markRead.calls.reset();
    pushed.next({
      id: 4,
      caseId: 29,
      sender: 'ANALYST',
      body: 'Che',
      createdAt: '2026-08-30T12:08:00Z',
    });
    expect(markRead).toHaveBeenCalled();

    markRead.calls.reset();
    pushed.next({
      id: 5,
      caseId: 29,
      sender: 'INSURED',
      body: 'Ahí va',
      createdAt: '2026-08-30T12:09:00Z',
    });
    expect(markRead).not.toHaveBeenCalled();
  });
});

describe('CaseChatComponent · composer', () => {
  let fixture: ComponentFixture<CaseChatComponent>;
  let posted: Subject<CaseMessage>;
  let post: jasmine.Spy;

  const thread: CaseMessageThread = {
    messages: [],
    unread: 0,
    canPost: true,
    closedNotice: null,
    topic: '/topic/cases.bbva.29',
    viewerSide: 'ANALYST',
  };

  beforeEach(async () => {
    posted = new Subject<CaseMessage>();
    post = jasmine.createSpy('post').and.callFake(() => posted.asObservable());

    await TestBed.configureTestingModule({
      imports: [CaseChatComponent],
      providers: [
        {
          provide: CaseMessagesService,
          useValue: {
            thread: () => of(thread),
            markRead: () => of(void 0),
            post,
            recordLatest: () => undefined,
            clearUnread: () => undefined,
          },
        },
        { provide: CaseMessagesSocketService, useValue: { watch: () => new Subject() } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CaseChatComponent);
    fixture.componentRef.setInput('caseId', 29);
    fixture.detectChanges();
  });

  function field(): HTMLTextAreaElement {
    return fixture.nativeElement.querySelector('.composer textarea');
  }

  function sendButton(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('.composer app-icon-button button');
  }

  function type(text: string): void {
    field().value = text;
    field().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function pressEnter(init: KeyboardEventInit = {}): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true, ...init });
    field().dispatchEvent(event);
    fixture.detectChanges();
    return event;
  }

  it('sends the message with Enter', () => {
    type('  Ya la subí.  ');
    const event = pressEnter();

    expect(post).toHaveBeenCalledOnceWith(29, 'Ya la subí.', null);
    expect(event.defaultPrevented).toBeTrue();
  });

  it('breaks the line with Shift+Enter instead of sending', () => {
    type('Primera línea');
    const event = pressEnter({ shiftKey: true });

    expect(post).not.toHaveBeenCalled();
    expect(event.defaultPrevented).toBeFalse();
  });

  it('does not send an empty or blank message with Enter', () => {
    pressEnter();
    type('   ');
    pressEnter();

    expect(post).not.toHaveBeenCalled();
  });

  it('does not send twice while the first send is on its way', () => {
    type('Ya la subí.');
    pressEnter();
    pressEnter();

    expect(post).toHaveBeenCalledTimes(1);
  });

  it('shows an arrow button named "Enviar", disabled until there is text', () => {
    expect(sendButton().getAttribute('aria-label')).toBe('Enviar');
    expect(sendButton().textContent!.trim()).toBe('');
    expect(sendButton().disabled).toBeTrue();

    type('Hola');

    expect(sendButton().disabled).toBeFalse();
  });

  it('sends the message with the arrow button', () => {
    type('Hola');
    fixture.nativeElement.querySelector('.composer app-icon-button').click();

    expect(post).toHaveBeenCalledOnceWith(29, 'Hola', null);
  });
});

describe('CaseChatComponent · case numbers in a message', () => {
  const body = 'Revisá el #42 y el expediente 7.\nGracias';

  async function render(viewerSide: 'ANALYST' | 'INSURED', insurer: string | null) {
    const thread: CaseMessageThread = {
      messages: [
        {
          id: 1,
          sender: viewerSide === 'ANALYST' ? 'INSURED' : 'ANALYST',
          mine: false,
          body,
          createdAt: '2026-08-30T12:05:00Z',
          readAt: null,
        },
      ],
      unread: 0,
      canPost: true,
      closedNotice: null,
      topic: '/topic/cases.bbva.29',
      viewerSide,
    };
    await TestBed.configureTestingModule({
      imports: [CaseChatComponent],
      providers: [
        provideRouter([]),
        {
          provide: CaseMessagesService,
          useValue: {
            thread: () => of(thread),
            markRead: () => of(void 0),
            post: () => new Subject(),
            recordLatest: () => undefined,
            clearUnread: () => undefined,
          },
        },
        { provide: CaseMessagesSocketService, useValue: { watch: () => new Subject() } },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(CaseChatComponent);
    fixture.componentRef.setInput('caseId', 29);
    fixture.componentRef.setInput('insurer', insurer);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function hrefs(host: HTMLElement): (string | null)[] {
    return Array.from(host.querySelectorAll('.body a')).map((a) => a.getAttribute('href'));
  }

  it('links each case number to the case screen for the analyst', async () => {
    const host = await render('ANALYST', null);

    expect(hrefs(host)).toEqual(['/cases/42', '/cases/7']);
    expect(Array.from(host.querySelectorAll('.body a')).map((a) => a.textContent)).toEqual([
      '#42',
      'expediente 7',
    ]);
  });

  it('links each case number to the portal, with the insurer, for the insured', async () => {
    const host = await render('INSURED', 'bbva');

    expect(hrefs(host)).toEqual(['/portal/cases/42?insurer=bbva', '/portal/cases/7?insurer=bbva']);
  });

  it('keeps the message text exactly as written', async () => {
    const host = await render('ANALYST', null);

    expect(host.querySelector('.body')!.textContent).toBe(body);
  });
});
