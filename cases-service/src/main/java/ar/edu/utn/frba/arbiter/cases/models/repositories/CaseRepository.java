package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
