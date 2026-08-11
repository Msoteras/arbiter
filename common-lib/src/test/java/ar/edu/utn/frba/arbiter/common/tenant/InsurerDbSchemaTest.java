package ar.edu.utn.frba.arbiter.common.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El pareo entre el esquema de Arbiter y el de la BD Aseguradora. Vale la pena testearlo aunque
 * sean cuatro líneas: cuando los dos adapters lo derivaban por su cuenta terminaron los dos
 * apuntando al esquema {@code aseguradora} pelado del modelo viejo, y eso solo se veía en un 500
 * después de un reseed limpio (D19).
 */
class InsurerDbSchemaTest {

    @Test
    void mapsATenantSchemaToItsInsurerDatabase() {
        assertThat(InsurerDbSchema.forTenant("arbiter_bbva")).isEqualTo("aseguradora_bbva");
        assertThat(InsurerDbSchema.forTenant("arbiter_provincia")).isEqualTo("aseguradora_provincia");
    }

    /**
     * El fallback de {@code TenantContext} cuando no hay tenant resuelto. No tiene BD Aseguradora
     * detrás: mejor romper acá que consultar un esquema inexistente y devolver "no tiene pólizas".
     */
    @Test
    void commonSchemaHasNoInsurerDatabase() {
        assertThatThrownBy(() -> InsurerDbSchema.forTenant("arbiter_common"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsSomethingThatIsNotATenantSchema() {
        assertThatThrownBy(() -> InsurerDbSchema.forTenant(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InsurerDbSchema.forTenant("aseguradora"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** El nombre se concatena en el SQL, así que un identificador raro no puede pasar. */
    @Test
    void rejectsANameThatIsNotAPlainIdentifier() {
        assertThatThrownBy(() -> InsurerDbSchema.forTenant("arbiter_bbva; DROP SCHEMA public"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InsurerDbSchema.forTenant("arbiter_BBVA"))
                .isInstanceOf(IllegalStateException.class);
    }
}
