package ar.edu.utn.frba.arbiter.classification.models.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * La foto de la póliza sobre la que se clasificó ({@code policy_snapshot}, el
 * {@code poliza_consultada} del DER).
 *
 * <p>La escribe este módulo y no cases-service porque acá es donde el dato entra a la decisión:
 * {@code ClassificationOrchestrator.fetchContext()} consulta la BD Aseguradora y de ahí salen dos
 * factores del scoring ({@code policy_standing} ← {@code payments_up_to_date},
 * {@code claim_frequency} ← {@code previous_claims}). cases-service, en el alta, ni toca la BD
 * Aseguradora: trabaja contra la copia local sincronizada. Que la fotografiara él daría un registro
 * de auditoría que no coincide con lo que la decisión usó, que es peor que no tener ninguno.
 *
 * <p>Sin esto la clasificación no es reproducible: la BD Aseguradora es un sistema externo que
 * sigue cambiando, así que "se puso al día" o "le liquidaron otro siniestro" alteran el score de
 * una re-corrida sin dejar rastro de qué se vio la primera vez — justo lo que la Disposición SSN
 * 2/2023 pide poder mostrar (D27).
 *
 * <p>JDBC plano y no una entidad, por la misma razón que {@link CaseOutcomeRepository}: mapear acá
 * una tabla del esquema de cases-service la dejaría con dos módulos dueños. La entidad
 * {@code PolicySnapshot} de cases-service es el lado <b>lectura</b>.
 */
@Repository
@RequiredArgsConstructor
public class PolicySnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param inForce si la póliza cubría temporalmente la fecha del hecho
     * @param payload la respuesta cruda de la BD Aseguradora, entera — es el registro fiel, del que
     *                las columnas de arriba son la lectura ya interpretada
     */
    public record Snapshot(
            String externalPolicyNumber,
            BigDecimal sumInsured,
            boolean inForce,
            boolean paymentsUpToDate,
            int previousClaims,
            String payload
    ) {}

    /**
     * Una sola fila por expediente: si ya hay foto, se pisa.
     *
     * <p>Es la decisión (b) del handoff. Un expediente se re-clasifica cuando el asegurado completa
     * documentación, y entre una corrida y la otra pasan horas: la póliza es la misma. Guardar una
     * fila por corrida agregaría filas sin {@code case_id} —la tabla no lo tiene, el vínculo es
     * {@code cases.policy_snapshot_id}— o sea huérfanas y difíciles de atribuir. Si algún día hace
     * falta el historial completo, es sumar {@code case_id} al DER y recién ahí acá.
     */
    public void save(Long caseId, Snapshot snapshot) {
        Long existingId = currentSnapshotId(caseId);
        if (existingId != null) {
            update(existingId, snapshot);
            return;
        }
        jdbcTemplate.update(
                "UPDATE cases SET policy_snapshot_id = ? WHERE id = ?", insert(snapshot), caseId);
    }

    /** Null si el expediente todavía no tiene foto, o si no está en este esquema. */
    private Long currentSnapshotId(Long caseId) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT policy_snapshot_id FROM cases WHERE id = ?",
                (rs, rowNum) -> rs.getObject("policy_snapshot_id", Long.class),
                caseId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Long insert(Snapshot snapshot) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO policy_snapshot (external_policy_number, sum_insured, in_force,
                                                     payments_up_to_date, previous_claims,
                                                     queried_at, insurer_db_payload)
                             VALUES (?, ?, ?, ?, ?, NOW(), ?::jsonb)
                          RETURNING id
                        """,
                Long.class,
                snapshot.externalPolicyNumber(), snapshot.sumInsured(), snapshot.inForce(),
                snapshot.paymentsUpToDate(), snapshot.previousClaims(), snapshot.payload());
    }

    /** {@code queried_at} se pisa también: la foto vigente es la de la última clasificación. */
    private void update(Long snapshotId, Snapshot snapshot) {
        jdbcTemplate.update("""
                        UPDATE policy_snapshot
                           SET external_policy_number = ?, sum_insured = ?, in_force = ?,
                               payments_up_to_date = ?, previous_claims = ?,
                               queried_at = NOW(), insurer_db_payload = ?::jsonb
                         WHERE id = ?
                        """,
                snapshot.externalPolicyNumber(), snapshot.sumInsured(), snapshot.inForce(),
                snapshot.paymentsUpToDate(), snapshot.previousClaims(), snapshot.payload(),
                snapshotId);
    }
}
