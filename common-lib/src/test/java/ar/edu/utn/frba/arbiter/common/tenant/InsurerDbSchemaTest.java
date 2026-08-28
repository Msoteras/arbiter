package ar.edu.utn.frba.arbiter.common.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pairing between Arbiter's schema and the insurer database's. Worth testing even at four
 * lines: when both adapters derived it on their own they ended up pointing at the old model's bare
 * {@code aseguradora} schema, and that only showed up as a 500 after a clean reseed (D19).
 */
class InsurerDbSchemaTest {

    @Test
    void mapsATenantSchemaToItsInsurerDatabase() {
        assertThat(InsurerDbSchema.forTenant("arbiter_bbva")).isEqualTo("aseguradora_bbva");
        assertThat(InsurerDbSchema.forTenant("arbiter_provincia")).isEqualTo("aseguradora_provincia");
    }

    /**
     * {@code TenantContext}'s fallback when no tenant was resolved. It has no insurer database
     * behind it: better to break here than to query a missing schema and answer "has no policies".
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

    /** The name is concatenated into the SQL, so an odd identifier can't get through. */
    @Test
    void rejectsANameThatIsNotAPlainIdentifier() {
        assertThatThrownBy(() -> InsurerDbSchema.forTenant("arbiter_bbva; DROP SCHEMA public"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InsurerDbSchema.forTenant("arbiter_BBVA"))
                .isInstanceOf(IllegalStateException.class);
    }
}
