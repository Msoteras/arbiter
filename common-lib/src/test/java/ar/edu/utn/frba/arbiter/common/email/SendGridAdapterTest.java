package ar.edu.utn.frba.arbiter.common.email;

import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SendGridAdapterTest {

    @Test
    void send_withoutApiKey_logsAndDoesNotCallSendGrid() {
        SendGridAdapter adapter = new SendGridAdapter("", "no-reply@arbiter.test", "Arbiter");

        try (MockedConstruction<SendGrid> mocked = mockConstruction(SendGrid.class)) {
            assertThatCode(() -> adapter.send("destinatario@example.com", "Asunto", "<p>Hola</p>"))
                    .doesNotThrowAnyException();

            // false, not just "no exception": quien anota "notificado" tiene que poder distinguir
            // esto de un envío real.
            assertThat(adapter.send("destinatario@example.com", "Asunto", "<p>Hola</p>")).isFalse();
            assertThat(mocked.constructed()).isEmpty();
        }
    }

    @Test
    void send_apiAccepts_doesNotThrow() throws IOException {
        SendGridAdapter adapter = new SendGridAdapter("fake-api-key", "no-reply@arbiter.test", "Arbiter");

        try (MockedConstruction<SendGrid> mocked = mockConstruction(SendGrid.class,
                (mock, context) -> when(mock.api(any(Request.class))).thenReturn(new Response(202, "", Map.of())))) {
            assertThat(adapter.send("destinatario@example.com", "Asunto", "<p>Hola</p>")).isTrue();

            verify(mocked.constructed().get(0)).api(any(Request.class));
        }
    }

    @Test
    void send_apiRejects_throwsEmailDeliveryException() throws IOException {
        SendGridAdapter adapter = new SendGridAdapter("fake-api-key", "no-reply@arbiter.test", "Arbiter");

        try (MockedConstruction<SendGrid> mocked = mockConstruction(SendGrid.class,
                (mock, context) -> when(mock.api(any(Request.class)))
                        .thenReturn(new Response(400, "Bad Request", Map.of())))) {
            assertThatThrownBy(() -> adapter.send("destinatario@example.com", "Asunto", "<p>Hola</p>"))
                    .isInstanceOf(EmailDeliveryException.class);
        }
    }

    @Test
    void send_ioException_throwsEmailDeliveryException() throws IOException {
        SendGridAdapter adapter = new SendGridAdapter("fake-api-key", "no-reply@arbiter.test", "Arbiter");

        try (MockedConstruction<SendGrid> mocked = mockConstruction(SendGrid.class,
                (mock, context) -> when(mock.api(any(Request.class))).thenThrow(new IOException("timeout")))) {
            assertThatThrownBy(() -> adapter.send("destinatario@example.com", "Asunto", "<p>Hola</p>"))
                    .isInstanceOf(EmailDeliveryException.class);
        }
    }
}
