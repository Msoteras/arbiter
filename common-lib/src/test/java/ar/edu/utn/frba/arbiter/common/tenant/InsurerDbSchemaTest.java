package ar.edu.utn.frba.arbiter.common.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsurerDbSchemaTest {

    @Test
    void mapsATenantSchemaToItsInsurerDatabase() {
        assertThat(InsurerDbSchema.forTenant("arbiter_bbva")).isEqualTo("aseguradora_bbva");
        assertThat(InsurerDbSchema.forTenant("arbiter_provincia")).isEqualTo("aseguradora_provincia");
    }

    /** Better to fail here than query a missing schema and answer "has no policies". */
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

    @Test
    void rejectsANameThatIsNotAPlainIdentifier() {
        assertThatThrownBy(() -> InsurerDbSchema.forTenant("arbiter_bbva; DROP SCHEMA public"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InsurerDbSchema.forTenant("arbiter_BBVA"))
                .isInstanceOf(IllegalStateException.class);
    }
}
