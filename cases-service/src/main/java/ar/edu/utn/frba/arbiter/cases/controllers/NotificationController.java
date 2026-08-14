package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.NotificationResponse;
import ar.edu.utn.frba.arbiter.cases.services.CaseNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The caller's own notifications. No role check: the account comes from the token and every query
 * is scoped by it, so an analyst has no more access here than an insured.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Avisos de cambios de estado del siniestro")
public class NotificationController {

    private final CaseNotificationService notificationService;

    @GetMapping
    @Operation(summary = "Mis notificaciones, de la más nueva a la más vieja")
    public ResponseEntity<List<NotificationResponse>> myNotifications() {
        return ResponseEntity.ok(notificationService.forCurrentUser());
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Contador de la campana, aparte del listado")
    public ResponseEntity<Long> unreadCount() {
        return ResponseEntity.ok(notificationService.unreadCountForCurrentUser());
    }

    @PostMapping("/read-all")
    @Operation(summary = "Marcar todas como leídas")
    public ResponseEntity<Void> markAllRead() {
        notificationService.markAllRead();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Marcar una notificación como leída")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return ResponseEntity.noContent().build();
    }
}
