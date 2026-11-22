package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.NotificationRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageNotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private CaseNotificationService caseNotificationService;

    @Mock
    private SendGridAdapter sendGridAdapter;

    @Mock
    private InsuredMailBranding mailBranding;

    @InjectMocks
    private MessageNotificationService service;

    @Test
    void theInsuredMailLinksTheCaseNumberAndTheCase() {
        Insured insured = Insured.builder()
                .id(1L)
                .email("laura@example.com")
                .user(User.builder().id(7L).build())
                .build();
        Case caseRecord = Case.builder().id(42L).insured(insured).build();
        when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(caseNotificationService.recipientEmail(insured)).thenReturn(Optional.of("laura@example.com"));
        when(mailBranding.caseUrl(42L)).thenReturn("https://arbiter.test/portal/cases/42?insurer=bbva");
        when(mailBranding.signature()).thenReturn("<p>Arbiter</p>");

        service.notifyNewMessage(caseRecord, StatusChangeActor.ANALYST);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sendGridAdapter).send(eq("laura@example.com"), anyString(), body.capture());
        assertThat(body.getValue()).contains(
                "Siniestro <strong><a href=\"https://arbiter.test/portal/cases/42?insurer=bbva\">#42</a></strong>.",
                "<a href=\"https://arbiter.test/portal/cases/42?insurer=bbva\">Ver tu siniestro</a>");
    }
}
