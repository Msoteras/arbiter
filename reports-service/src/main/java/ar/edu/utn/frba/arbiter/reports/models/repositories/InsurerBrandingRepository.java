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
     * Outside any transaction: a failed statement inside one is marked rollback-only and the
     * fallback never runs. A header nobody could read must not cost the referent the export.
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
