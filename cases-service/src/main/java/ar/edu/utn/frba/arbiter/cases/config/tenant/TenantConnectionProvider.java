package ar.edu.utn.frba.arbiter.cases.config.tenant;

import lombok.RequiredArgsConstructor;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/** One shared {@link DataSource}; the tenant is selected per connection via {@code SET search_path}. */
@Component
@RequiredArgsConstructor
public class TenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    // Defense in depth: schema names are server-controlled, but identifiers can't be bind parameters.
    private static final Pattern SAFE_SCHEMA = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private final DataSource dataSource;

    /**
     * Used mainly by Hibernate's boot-time schema validation, which needs some tenant schema on the
     * search_path; all tenant schemas are identical, so the first active one is picked. Best-effort:
     * without {@code arbiter_common} (fresh database, flat test schema) it keeps the default search_path.
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
        }
        return connection;
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    /**
     * Deliberately not via {@link #getAnyConnection()}: that one queries the insurer registry, adding
     * two round trips before every query when the schema is already known.
     */
    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = dataSource.getConnection();
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
