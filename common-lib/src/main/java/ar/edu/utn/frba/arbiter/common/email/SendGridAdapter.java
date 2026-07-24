package ar.edu.utn.frba.arbiter.common.email;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Wraps the SendGrid SDK behind a single generic method — user invitations, password resets, or
 * future notifications (status change to the insured) each build their own subject/HTML and hit
 * this same {@link #send}, without touching the SDK directly (see CLAUDE.md, Adapter pattern).
 * With no API key configured, it just logs — lets the rest of the flow (tokens, endpoints) run
 * and get tested without depending on a real SendGrid account.
 */
public class SendGridAdapter {

    private static final Logger log = LoggerFactory.getLogger(SendGridAdapter.class);

    private final String apiKey;
    private final String fromAddress;
    private final String fromName;

    public SendGridAdapter(String apiKey, String fromAddress, String fromName) {
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    public void send(String to, String subject, String htmlBody) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("SENDGRID_API_KEY no configurada — no se manda el mail a {} (asunto: {})", to, subject);
            return;
        }

        Mail mail = new Mail(new Email(fromAddress, fromName), subject, new Email(to), new Content("text/html", htmlBody));

        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint("mail/send");
        try {
            request.setBody(mail.build());
            Response response = new SendGrid(apiKey).api(request);
            if (response.getStatusCode() >= 300) {
                throw new EmailDeliveryException(to, response.getStatusCode(), response.getBody());
            }
        } catch (IOException e) {
            throw new EmailDeliveryException(to, e);
        }
    }
}
