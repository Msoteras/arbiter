package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The caller's insurer, for the header of an exported report.
 *
 * <p>Read-only plain JDBC over named columns like the rest of this module. Two deliberate differences
 * from {@link ResolvedCaseRepository}: the table is schema-qualified and the query runs on a pooled
 * connection rather than Hibernate's, because {@code insurer} is the platform's own table and must
 * not resolve through whichever tenant {@code search_path} the request happens to carry.
 */
@Repository
@RequiredArgsConstructor
public class InsurerBrandingRepository {

    private static final Logger log = LoggerFactory.getLogger(InsurerBrandingRepository.class);

    private static final String BY_SCHEMA = """
            SELECT name, legal_name
              FROM arbiter_common.insurer
             WHERE schema_name = :schemaName
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * <b>A header nobody could read doesn't take the export down.</b> The identity is decoration on
     * top of the figures, so an unreachable common schema logs and falls back to the neutral header
     * instead of failing a report the referent asked for. Runs outside any transaction on purpose: a
     * failed statement inside one would mark it rollback-only and the fallback would never be reached.
     *
     * @return empty if no insurer owns that schema, or if the lookup failed
     */
    public Optional<ReportBranding> findBySchemaName(String schemaName) {
        try {
            return Optional.ofNullable(jdbcTemplate.query(
                    BY_SCHEMA,
                    new MapSqlParameterSource("schemaName", schemaName),
                    rs -> rs.next()
                            ? new ReportBranding(rs.getString("name"), rs.getString("legal_name"))
                            : null));
        } catch (DataAccessException unavailable) {
            log.warn("[Reports] could not read the insurer of schema {}, the export goes with the "
                    + "neutral header: {}", schemaName, unavailable.getMessage());
            return Optional.empty();
        }
    }
}
