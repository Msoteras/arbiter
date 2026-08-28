package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public interface CaseRepository extends JpaRepository<Case, Long>, JpaSpecificationExecutor<Case> {

    /**
     * Cases sitting in a given state. Takes the enum and navigates to {@code case_status.name}
     * rather than the FK id, so callers never need to know the catalog's ids.
     */
    default List<Case> findByStatus(CaseStatus status) {
        return findByCurrentStatusName(status.name());
    }

    List<Case> findByCurrentStatusName(String statusName);

    List<Case> findByRiskBand(RiskBand riskBand);

    /**
     * Cases stuck in {@code CLASSIFICATION_FAILED} for a reason worth auto-requeuing —
     * {@code ClassificationRefreshScheduler.recoverInfrastructureFailures}. Same enum-to-name
     * navigation as {@link #findByStatus}: the state itself is a catalog row, not a column.
     */
    default List<Case> findFailedByReason(ClassificationFailureReason reason) {
        return findByCurrentStatusNameAndClassificationFailureReason(
                CaseStatus.CLASSIFICATION_FAILED.name(), reason);
    }

    List<Case> findByCurrentStatusNameAndClassificationFailureReason(
            String statusName, ClassificationFailureReason reason);

    /**
     * Bumps only the attempt counter, without touching the rest of the row.
     *
     * <p>{@code ClassificationRefreshScheduler} used {@code save(caseRecord)} for this, which
     * writes the <b>whole</b> entity from a copy loaded at the start of the sweep — so any change
     * made to that case in between (an analyst's retry, a status transition, a manual fix) got
     * silently reverted on the next tick, every few seconds. The counter is the only thing the
     * sweep owns, so it's the only thing it should write.
     *
     * <p>{@code flushAutomatically}/{@code clearAutomatically} keep the in-memory entity from
     * shadowing the value this just wrote.
     *
     * <p>{@code @Transactional} explícito: llamado desde el scheduler (sin request/JWT detrás),
     * el auto-wrap transaccional que Spring Data le da por defecto a un método {@code @Modifying}
     * no alcanzaba a cubrir el {@code flush} — mismo síntoma que rompía en
     * {@code CaseStatusService} y en {@code ClassificationResultsService.getStatus}
     * ("No EntityManager with actual transaction available ... cannot reliably process 'flush'
     * call"), acá también resuelto haciendo la transacción explícita en vez de confiar en la
     * implícita.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Case c set c.classificationAttempts = :attempts where c.id = :caseId")
    void updateClassificationAttempts(@Param("caseId") Long caseId, @Param("attempts") int attempts);

    /**
     * Igual que {@link #updateClassificationAttempts}, pero <b>condicional</b>: sólo avanza el
     * contador si en la base sigue valiendo lo que el barrido leyó. Un compare-and-set.
     *
     * <p>Existe porque el contador lo escriben varios barridos a la vez. No es hipotético: la base
     * de Railway es <b>compartida por todo el equipo</b>, así que cada desarrollador que levanta el
     * stack local suma un {@code ClassificationRefreshScheduler} más barriendo LOS MISMOS
     * expedientes. Sin condición, dos barridos leen 120, los dos escriben 121, y los dos se creen
     * con derecho a marcar el expediente como fallido — de ahí las transiciones duplicadas en
     * {@code case_status_history}, dos filas con el mismo número de reintentos separadas por
     * segundos.
     *
     * <p>Con la condición, el segundo actualiza 0 filas y se retira: el avance del contador es lo
     * que hace de turno, y sólo uno se lo puede quedar por vuelta.
     *
     * @param expected el valor que el barrido leyó; si ya cambió, no se pisa
     * @return 1 si este barrido se quedó con el turno, 0 si otro llegó primero
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Case c
               set c.classificationAttempts = :attempts
             where c.id = :caseId
               and c.classificationAttempts = :expected
            """)
    int advanceClassificationAttempts(@Param("caseId") Long caseId,
                                      @Param("expected") int expected,
                                      @Param("attempts") int attempts);

    /**
     * El compare-and-set del barrido de recuperación, con el mismo rol que
     * {@link #advanceClassificationAttempts} tiene en el otro: quedarse con el turno.
     *
     * <p>Limpiar el motivo es lo que hace de token porque es el campo que significa exactamente
     * "este expediente está esperando que lo reencolen": el que consigue pasarlo de
     * {@code INFRASTRUCTURE} a null se lo quedó, el que llega tarde actualiza 0 filas y se retira.
     * Sin esto, dos barridos leen el mismo {@code CLASSIFICATION_FAILED}, los dos pasan el filtro y
     * los dos reencolan — dos clasificaciones y dos filas en {@code case_status_history}, el mismo
     * bug que el CAS del otro barrido existe para prevenir. Con un solo proceso no se nota; con la
     * instancia desplegada corriendo al lado de los stacks locales del equipo, sí.
     *
     * <p>Limpiarlo además es correcto por sí solo: el motivo describe la corrida anterior, y a
     * partir de acá el expediente vuelve a clasificarse. Si la corrida nueva falla, se escribe uno
     * fresco ({@code CaseOutcomeRepository.recordClassificationFailure}); si sale bien, ya estaba
     * limpio ({@code ClassificationResultsService.saveResult} lo limpia igual).
     *
     * <p><b>Ventana conocida:</b> si la transición de estado falla justo después de este update, el
     * expediente queda {@code CLASSIFICATION_FAILED} sin motivo y este barrido no lo vuelve a
     * levantar — sale por el botón manual del analista. Es una ventana angosta (un error de base
     * entre dos escrituras) y se prefirió a la alternativa, que es reencolar de más.
     *
     * @return 1 si este barrido se quedó con el turno, 0 si otro llegó primero
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Case c
               set c.classificationFailureReason = null
             where c.id = :caseId
               and c.classificationFailureReason = :expected
            """)
    int claimFailedCaseForRequeue(@Param("caseId") Long caseId,
                                  @Param("expected") ClassificationFailureReason expected);

    /** Fila de {@link #countActiveByAnalyst(Collection)}: un analista y cuántos activos tiene. */
    interface AnalystCaseCount {
        Long getAnalystId();

        long getTotal();
    }

    /**
     * Cuenta expedientes ACTIVOS (los que no están en un estado final) agrupados por analista
     * asignado, en un solo query por el esquema del tenant activo. Solo aparecen analistas con al
     * menos un expediente activo; los que no tienen ninguno los completa el service con cero, para
     * que el panel muestre a todo el equipo. Los expedientes sin asignar quedan afuera
     * ({@code c.analyst is not null}).
     *
     * @param finalStatuses nombres de los estados terminales a excluir (APPROVED, REJECTED)
     */
    @Query("""
            select c.analyst.id as analystId, count(c) as total
            from Case c
            where c.analyst is not null
              and c.currentStatus.name not in :finalStatuses
            group by c.analyst.id
            """)
    List<AnalystCaseCount> countActiveByAnalyst(@Param("finalStatuses") Collection<String> finalStatuses);

    /** Fila de {@link #countByStatusForAnalyst(Long)}: un estado y cuántos expedientes hay en él. */
    interface StatusCount {
        String getStatus();

        long getTotal();
    }

    /**
     * Cuenta los expedientes asignados a un analista agrupados por estado, en un solo query. Base
     * del resumen del inicio del analista (pendientes / en trámite / resueltos). Solo aparecen los
     * estados con al menos un expediente; el resto es cero.
     */
    @Query("""
            select c.currentStatus.name as status, count(c) as total
            from Case c
            where c.analyst.id = :analystId
            group by c.currentStatus.name
            """)
    List<StatusCount> countByStatusForAnalyst(@Param("analystId") Long analystId);

    /** Cuántos expedientes del analista tienen una de las bandas de riesgo dadas (ej. HIGH, CRITICAL). */
    @Query("""
            select count(c)
            from Case c
            where c.analyst.id = :analystId
              and c.riskBand in :bands
            """)
    long countByAnalystAndRiskBandIn(@Param("analystId") Long analystId,
                                     @Param("bands") Collection<RiskBand> bands);
}
