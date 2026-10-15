package ar.edu.utn.frba.arbiter.cases.dto;

import java.util.List;

/**
 * Fetching the thread doesn't mark anything read; that is a separate, explicit call.
 *
 * @param canPost      decided server-side so the clients don't each reimplement the closing rule
 * @param closedNotice why the caller can't post: the thread is closed, or the caller only reads it. Null
 *                     when {@code canPost}
 * @param topic        handed over rather than built by the client, since it includes the tenant
 * @param viewerSide   lets the client place pushed messages (one payload for both sides). Null for a referente.
 */
public record CaseMessageThreadResponse(
        List<CaseMessageResponse> messages,
        int unread,
        boolean canPost,
        String closedNotice,
        String topic,
        String viewerSide
) {}
