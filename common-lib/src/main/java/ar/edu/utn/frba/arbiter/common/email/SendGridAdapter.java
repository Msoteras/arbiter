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
 * Encapsula el SDK de SendGrid detrás de un único método genérico — invitación de usuarios,
 * reset de contraseña o notificaciones futuras (cambio de estado al asegurado) arman su propio
 * asunto/HTML y le pegan a este mismo {@link #send}, sin tocar el SDK directo (ver CLAUDE.md,
 * patrón Adapter). Sin API key configurada, solo logea — permite correr y testear el resto del
 * flujo (tokens, endpoints) sin depender de una cuenta real de SendGrid.
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
