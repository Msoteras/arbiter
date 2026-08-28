package ar.edu.utn.frba.arbiter.common.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Which insurer a user belongs to ("usuario_aseguradora" in the DER) — a pure junction
 * row, composite PK {@code (user_id, insurer_id)} in the real DDL, no surrogate id
 * column. {@code insurerId} is a real FK now that {@link Insurer} lives in the same
 * common schema (unlike the single-schema DB, where rules-service owned it and this had
 * to be a logical cross-module reference instead).
 */
@Entity
@Table(name = "user_insurer", schema = "arbiter_common")
@IdClass(UserInsurer.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserInsurer {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Id
    @Column(name = "insurer_id", nullable = false)
    private Long insurerId;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long user;
        private Long insurerId;
    }
}
