package ar.edu.utn.frba.arbiter.common.email;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Attachments;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * Wraps the SendGrid SDK behind a generic {@link #send}. With no API key configured it only logs,
 * so the rest of the flow runs without a real SendGrid account.
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

    /**
     * @return {@code false} when there is no API key and nothing was sent, so callers don't record
     *     a notification that never happened. Delivery failures throw {@link EmailDeliveryException}.
     */
    public boolean send(String to, String subject, String htmlBody) {
        return send(to, subject, htmlBody, List.of());
    }

    /** SendGrid caps a message at 30 MB including base64 overhead; the caller decides what fits. */
    public boolean send(String to, String subject, String htmlBody, List<Attachment> attachments) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("SENDGRID_API_KEY no configurada — no se manda el mail a {} (asunto: {})", to, subject);
            return false;
        }

        Mail mail = new Mail(new Email(fromAddress, fromName), subject, new Email(to), new Content("text/html", htmlBody));
        attachments.forEach(attachment -> mail.addAttachments(toSendGrid(attachment)));

        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint("mail/send");
        try {
            request.setBody(mail.build());
            Response response = new SendGrid(apiKey).api(request);
            if (response.getStatusCode() >= 300) {
                throw new EmailDeliveryException(to, response.getStatusCode(), response.getBody());
            }
            return true;
        } catch (IOException e) {
            throw new EmailDeliveryException(to, e);
        }
    }

    private Attachments toSendGrid(Attachment attachment) {
        return new Attachments.Builder(
                attachment.filename(), Base64.getEncoder().encodeToString(attachment.content()))
                .withType(attachment.contentType())
                .withDisposition("attachment")
                .build();
    }

    public record Attachment(String filename, String contentType, byte[] content) {}
}
