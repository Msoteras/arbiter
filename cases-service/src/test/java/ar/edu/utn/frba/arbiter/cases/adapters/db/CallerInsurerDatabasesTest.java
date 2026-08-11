package ar.edu.utn.frba.arbiter.cases.adapters.db;

import ar.edu.utn.frba.arbiter.cases.adapters.db.CallerInsurerDatabases.InsurerDatabase;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Qué esquemas de BD Aseguradora puede leer el que llama. Es el reemplazo del esquema
 * {@code aseguradora} único del modelo viejo (D19): ahora hay uno por compañía y el portal tiene
 * que recorrer los del asegurado para que la vista centralizada siga existiendo.
 */
@ExtendWith(MockitoExtension.class)
class CallerInsurerDatabasesTest {

    private static final String CALLER_TENANT = "arbiter_bbva";

    @Mock
    private InsurerRepository insurerRepository;

    @InjectMocks
    private CallerInsurerDatabases databases;

    @AfterEach
    void clearContext() {
        CallerContext.clear();
        TenantContext.clear();
    }

    private Insurer insurer(Long id, String name, String schema, boolean active) {
        Insurer insurer = new Insurer();
        insurer.setId(id);
        insurer.setName(name);
        insurer.setSchemaName(schema);
        insurer.setActive(active);
        return insurer;
    }

    /** El caso de Martina: cliente de dos compañías con el mismo DNI. */
    @Test
    void resolvesOneDatabasePerInsurerOfTheCaller() {
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "BBVA Seguros", "arbiter_bbva", true),
                insurer(2L, "Provincia Seguros", "arbiter_provincia", true)));

        assertThat(databases.forCaller()).containsExactly(
                new InsurerDatabase(1L, "BBVA Seguros", "aseguradora_bbva"),
                new InsurerDatabase(2L, "Provincia Seguros", "aseguradora_provincia"));
    }

    @Test
    void skipsInactiveInsurers() {
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "BBVA Seguros", "arbiter_bbva", false),
                insurer(2L, "Provincia Seguros", "arbiter_provincia", true)));

        assertThat(databases.forCaller())
                .extracting(InsurerDatabase::schema)
                .containsExactly("aseguradora_provincia");
    }

    /** Sin el claim (token viejo, o llamada sin usuario detrás) queda el tenant ya resuelto. */
    @Test
    void withoutInsurerIdsClaim_fallsBackToTheCurrentTenant() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(), CALLER_TENANT));
        when(insurerRepository.findBySchemaName(CALLER_TENANT))
                .thenReturn(Optional.of(insurer(1L, "BBVA Seguros", "arbiter_bbva", true)));

        assertThat(databases.forCaller())
                .containsExactly(new InsurerDatabase(1L, "BBVA Seguros", "aseguradora_bbva"));
    }

    /** Un tenant que no está en el registro no tiene BD Aseguradora que ofrecer. */
    @Test
    void withoutInsurerIdsClaimAndAnUnknownTenant_resolvesNothing() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(), CALLER_TENANT));
        when(insurerRepository.findBySchemaName(CALLER_TENANT)).thenReturn(Optional.empty());

        assertThat(databases.forCaller()).isEmpty();
    }
}
