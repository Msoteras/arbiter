package ar.edu.utn.frba.arbiter.cases.dto;

import java.util.List;

/**
 * A case's whole conversation plus what the UI needs around it. {@code unread} travels with the
 * thread so opening a case costs one call and still lights up the tab's dot — reading does not mark
 * anything read, that is an explicit call made when the reader actually looks.
 *
 * @param canPost      whether the caller may write right now, decided server-side so the two
 *                     clients don't each reimplement the closing rule and drift apart
 * @param closedNotice why not, written for whoever is reading it; null while the thread is open
 * @param topic        the STOMP destination for this thread. Handed over rather than derived by the
 *                     client: the tenant is part of it and the client has no business building it.
 * @param viewerSide   which side the caller is on, so a pushed message —which has one payload for
 *                     both— can be placed without asking the server again. Null for a referente.
 */
public record CaseMessageThreadResponse(
        List<CaseMessageResponse> messages,
        int unread,
        boolean canPost,
        String closedNotice,
        String topic,
        String viewerSide
) {}
