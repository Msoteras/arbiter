package ar.edu.utn.frba.arbiter.reports.config.tenant;

import lombok.RequiredArgsConstructor;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * One shared {@link DataSource}, one schema per tenant — switches which schema a
 * borrowed connection sees via {@code SET search_path}, not a different DataSource per
 * tenant (there's only one database). Same implementation as auth-service's.
 */
@Component
@RequiredArgsConstructor
public class TenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    // Schema names come from insurer.schema_name (server-controlled, set at seed/onboarding
    // time), never from request input directly — this is still a defense-in-depth guard
    // against building an unsafe SET search_path string, since identifiers can't be bind
    // parameters.
    private static final Pattern SAFE_SCHEMA = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private final DataSource dataSource;

    /**
     * A connection with no tenant resolved yet — Hibernate's boot-time schema validation is
     * the main caller — needs SOME schema on its search_path to see anything: entities live
     * split between {@code arbiter_common} and each tenant's own schema, and Postgres defaults
     * an unqualified connection to {@code public}, where none of this app's tables are. Every
     * tenant schema is structurally identical (the same {@code create_tenant_schema} function
     * builds them all), so which one gets picked doesn't matter for validation purposes —
     * this reads the first active one off the registry itself.
     *
     * <p>Best-effort: if the query fails — a fresh database before {@code init-multitenant.sql}
     * ran, or the flat single-schema layout {@code AbstractPersistenceIT} builds for tests,
     * where {@code arbiter_common} doesn't exist as a schema at all — this falls back to the
     * connection's own default rather than throwing, which is exactly today's behavior.
     */
    @Override
    public Connection getAnyConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT schema_name FROM " + TenantContext.COMMON_SCHEMA
                             + ".insurer WHERE active = true ORDER BY id LIMIT 1")) {
            if (rs.next()) {
                applySearchPath(connection, rs.getString(1));
            }
        } catch (SQLException noRegistryYet) {
            // See the Javadoc: with no arbiter_common yet, stay on the default search_path.
        }
        return connection;
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = getAnyConnection();
        applySearchPath(connection, tenantIdentifier);
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        // HikariCP does not reset session state on its own — leaving this set would leak
        // the tenant into whatever request borrows the connection next.
        applySearchPath(connection, TenantContext.COMMON_SCHEMA);
        releaseAnyConnection(connection);
    }

    private void applySearchPath(Connection connection, String schema) throws SQLException {
        if (!SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Unsafe schema identifier: " + schema);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema + ", " + TenantContext.COMMON_SCHEMA + ", public");
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        throw new UnsupportedOperationException("Cannot unwrap TenantConnectionProvider as " + unwrapType);
    }
}
