package ar.edu.utn.frba.arbiter.cases.adapters.db;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.tenant.InsurerDbSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Las "BD Aseguradora" que el que llama puede leer: una por aseguradora a la que pertenece.
 *
 * <p>Existe porque el portal es <b>multi-aseguradora</b> — Martina es clienta de BBVA y de
 * Provincia con el mismo DNI — pero cada compañía tiene su propio esquema
 * ({@code aseguradora_bbva}, {@code aseguradora_provincia}). Sin esto, "mis pólizas" mostraría
 * solo las de la aseguradora que ganó el sorteo en el login. Es el mismo problema que resuelve
 * {@link ar.edu.utn.frba.arbiter.cases.services.InsuredCaseAggregator} para los expedientes.
 *
 * <p>El conjunto sale de {@code insurerIds}, un claim <b>firmado</b> del JWT, nunca de un
 * parámetro del request: es lo único que impide que alguien nombre el esquema de una compañía a
 * la que no pertenece.
 */
@Component
@RequiredArgsConstructor
public class CallerInsurerDatabases {

    /**
     * @param insurerId   el id de la aseguradora en la plataforma, no el de su {@code compania}:
     *                    con un esquema por compañía ese id es siempre 1 y no identifica nada
     * @param insurerName nombre corto, el mismo con el que la aseguradora aparece en el resto del
     *                    portal
     * @param schema      su esquema de BD Aseguradora
     */
    public record InsurerDatabase(Long insurerId, String insurerName, String schema) {
    }

    private final InsurerRepository insurerRepository;

    /** En orden de nombre, para que el listado del portal no cambie entre llamadas. */
    public List<InsurerDatabase> forCaller() {
        List<Long> insurerIds = CallerContext.get().insurerIds();
        List<Insurer> insurers = insurerIds.isEmpty()
                // Sin el claim (token viejo, o una llamada sin usuario detrás) queda el tenant ya
                // resuelto: el comportamiento de antes, no un error nuevo.
                ? insurerRepository.findBySchemaName(TenantContext.get()).stream().toList()
                : insurerRepository.findAllById(insurerIds);

        return insurers.stream()
                .filter(Insurer::isActive)
                .sorted(Comparator.comparing(Insurer::getName))
                .map(insurer -> new InsurerDatabase(
                        insurer.getId(),
                        insurer.getName(),
                        InsurerDbSchema.forTenant(insurer.getSchemaName())))
                .toList();
    }
}
