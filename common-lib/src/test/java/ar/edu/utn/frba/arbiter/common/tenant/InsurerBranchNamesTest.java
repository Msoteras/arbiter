package ar.edu.utn.frba.arbiter.common.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InsurerBranchNamesTest {

    private final JdbcOperations jdbc = mock(JdbcOperations.class);

    /** Provincia renamed "Celulares" to "Equipos Móviles"; its policies still arrive as "Celulares". */
    @Test
    void translatesTheInsurerDatabaseNameIntoTheRenamedBranch() {
        tenantHasBranch("arbiter_provincia", "Celulares", "Equipos Móviles");

        InsurerBranchNames names = InsurerBranchNames.load(jdbc, "arbiter_provincia");

        assertThat(names.nameOf("Celulares")).isEqualTo("Equipos Móviles");
    }

    @Test
    void leavesALineTheInsurerNeverCataloguedAsItArrived() {
        tenantHasBranch("arbiter_bbva", "Celulares", "Celulares");

        InsurerBranchNames names = InsurerBranchNames.load(jdbc, "arbiter_bbva");

        assertThat(names.nameOf("Hogar")).isEqualTo("Hogar");
        assertThat(names.nameOf(null)).isNull();
    }

    @Test
    void rejectsASchemaNameThatIsNotAPlainIdentifier() {
        assertThatThrownBy(() -> InsurerBranchNames.load(jdbc, "arbiter_bbva; DROP TABLE branch"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InsurerBranchNames.load(jdbc, null))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }

    private void tenantHasBranch(String tenantSchema, String externalName, String name) {
        doAnswer(invocation -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getString("external_name")).thenReturn(externalName);
            when(row.getString("name")).thenReturn(name);
            invocation.<RowCallbackHandler>getArgument(1).processRow(row);
            return null;
        }).when(jdbc).query(
                eq("SELECT external_name, name FROM " + tenantSchema + ".branch WHERE external_name IS NOT NULL"),
                any(RowCallbackHandler.class));
    }
}
