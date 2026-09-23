package ar.edu.utn.frba.arbiter.cases.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** Only {@code readAt} is mutable: a decision may hang off what the other party already read. */
@Entity
@Table(name = "case_message")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    /** Logical reference to a user in {@code arbiter_common}, like {@code Notification.recipientId}. */
    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    /** Frozen at write time so it survives role changes. Only {@code INSURED} or {@code ANALYST}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 30)
    private StatusChangeActor senderRole;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the other side read it. */
    @Setter
    @Column(name = "read_at")
    private Instant readAt;
}
