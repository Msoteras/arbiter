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
 */
public record CaseMessageThreadResponse(
        List<CaseMessageResponse> messages,
        int unread,
        boolean canPost,
        String closedNotice
) {}
