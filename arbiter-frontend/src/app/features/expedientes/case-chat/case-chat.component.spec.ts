import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of } from 'rxjs';

import { CaseChatComponent } from './case-chat.component';
import { CaseMessagesService } from '../case-messages.service';
import { CaseMessagesSocketService } from '../case-messages-socket.service';
import { CaseMessageEvent, CaseMessageThread } from '../../../core/models/case-message';

/** The socket frame is shared by both sides and carries no `mine`: the component must place it. */
describe('CaseChatComponent · messages over the socket', () => {
  let fixture: ComponentFixture<CaseChatComponent>;
  let pushed: Subject<CaseMessageEvent>;
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
    topic: '/topic/cases/bbva/29',
    viewerSide: 'INSURED',
  };

  beforeEach(async () => {
    pushed = new Subject<CaseMessageEvent>();
    markRead = jasmine.createSpy('markRead').and.returnValue(of(void 0));

    await TestBed.configureTestingModule({
      imports: [CaseChatComponent],
      providers: [
        {
          provide: CaseMessagesService,
          useValue: {
            thread: () => of(thread),
            markRead,
            post: () => of(null),
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
        who: node.querySelector('.who')!.textContent!.trim(),
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
