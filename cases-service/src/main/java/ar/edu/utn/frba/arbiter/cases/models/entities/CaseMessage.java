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

/**
 * One message in the conversation an analyst and an insured have about a case. Separate from
 * {@link Notification}, which is outbound and automatic: this one has two sides and somebody typed
 * it, so it has to be answerable.
 *
 * <p>Only {@code readAt} is mutable — editing a message the other party already read would rewrite
 * a conversation a decision may hang off.
 */
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

    /**
     * Which side wrote it, frozen at write time so it keeps reading right if that person later
     * changes role or leaves. Only {@code INSURED} and {@code ANALYST} are ever written: a
     * referente reads the thread but doesn't post to it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 30)
    private StatusChangeActor senderRole;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the <b>other</b> side read it. Null is what the unread badge counts. */
    @Setter
    @Column(name = "read_at")
    private Instant readAt;
}
