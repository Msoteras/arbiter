package ar.edu.utn.frba.arbiter.common.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InsurerCoverageNamesTest {

    private final JdbcOperations jdbc = mock(JdbcOperations.class);

    @Test
    void translatesTheInsurerDatabaseNameIntoTheRenamedCoverage() {
        tenantHasCoverages("arbiter_bbva", new String[] {"Celulares", "Robo de celular", "Robo"});

        InsurerCoverageNames names = InsurerCoverageNames.load(jdbc, "arbiter_bbva");

        assertThat(names.nameOf("Celulares", "Robo de celular")).isEqualTo("Robo");
    }

    /** The insurer database names the laptop theft cover "Robo de celular" too. */
    @Test
    void resolvesTheSameInsurerNamePerLine() {
        tenantHasCoverages("arbiter_provincia",
                new String[] {"Celulares", "Robo de celular", "Robo"},
                new String[] {"Tecnología Portátil", "Robo de celular", "Robo de equipo"});

        InsurerCoverageNames names = InsurerCoverageNames.load(jdbc, "arbiter_provincia");

        assertThat(names.nameOf("Celulares", "Robo de celular")).isEqualTo("Robo");
        assertThat(names.nameOf("Tecnología Portátil", "Robo de celular")).isEqualTo("Robo de equipo");
    }

    @Test
    void leavesACoverageTheInsurerNeverCataloguedAsItArrived() {
        tenantHasCoverages("arbiter_bbva", new String[] {"Celulares", "Hurto", "Hurto"});

        InsurerCoverageNames names = InsurerCoverageNames.load(jdbc, "arbiter_bbva");

        assertThat(names.nameOf("Celulares", "Incendio")).isEqualTo("Incendio");
        assertThat(names.nameOf("Hogar", "Hurto")).isEqualTo("Hurto");
        assertThat(names.nameOf("Celulares", null)).isNull();
    }

    @Test
    void rejectsASchemaNameThatIsNotAPlainIdentifier() {
        assertThatThrownBy(() -> InsurerCoverageNames.load(jdbc, "arbiter_bbva; DROP TABLE coverage"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InsurerCoverageNames.load(jdbc, null))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }

    /** Each row is {branch external name, coverage external name, coverage name}. */
    private void tenantHasCoverages(String tenantSchema, String[]... rows) {
        doAnswer(invocation -> {
            for (String[] values : rows) {
                ResultSet row = mock(ResultSet.class);
                when(row.getString("branch_external_name")).thenReturn(values[0]);
                when(row.getString("external_name")).thenReturn(values[1]);
                when(row.getString("name")).thenReturn(values[2]);
                invocation.<RowCallbackHandler>getArgument(1).processRow(row);
            }
            return null;
        }).when(jdbc).query(contains("FROM " + tenantSchema + ".coverage c"), any(RowCallbackHandler.class));
    }
}
