package ar.edu.utn.frba.arbiter.rules.config.tenant;

import lombok.RequiredArgsConstructor;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/** One shared {@link DataSource}; each borrowed connection is pointed at its tenant via {@code SET search_path}. */
@Component
@RequiredArgsConstructor
public class TenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    // Schema names are server-controlled, but identifiers can't be bind parameters, so guard the
    // SET search_path string anyway.
    private static final Pattern SAFE_SCHEMA = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private final DataSource dataSource;

    /**
     * A connection with no tenant yet (mainly Hibernate's boot-time schema validation) still needs a
     * tenant schema on its search_path, or it only sees {@code public}. All tenant schemas are
     * identical, so the first active one is used. Best-effort: with no registry yet (fresh database
     * or tests), it keeps the default search_path instead of throwing.
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
            // No arbiter_common yet: stay on the default search_path.
        }
        return connection;
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    /**
     * Deliberately skips {@link #getAnyConnection()}: the schema is already known, and going through
     * it costs two extra round-trips to the database before every query.
     */
    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = dataSource.getConnection();
        applySearchPath(connection, tenantIdentifier);
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        // HikariCP doesn't reset session state: leaving this set would leak the tenant into the
        // next request that borrows the connection.
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
