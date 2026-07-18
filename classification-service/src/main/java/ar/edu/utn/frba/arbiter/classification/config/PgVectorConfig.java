package ar.edu.utn.frba.arbiter.classification.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "arbiter.embedding.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PgVectorConfig {

    private static final Logger log = LoggerFactory.getLogger(PgVectorConfig.class);

    private final JdbcTemplate jdbcTemplate;

    private static final int EMBEDDING_DIMS = 512; // CLIP ViT-B-32

    @PostConstruct
    void ensurePgVector() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");

        // If the column exists with a different dimension (e.g. from an older
        // embedding model), drop it — stored vectors are incompatible anyway.
        jdbcTemplate.execute("""
                DO $$
                DECLARE current_dims int;
                BEGIN
                    SELECT atttypmod INTO current_dims
                    FROM pg_attribute
                    WHERE attrelid = 'image_embedding'::regclass
                      AND attname = 'embedding'
                      AND NOT attisdropped;
                    IF current_dims IS NOT NULL AND current_dims <> %d THEN
                        EXECUTE 'ALTER TABLE image_embedding DROP COLUMN embedding';
                    END IF;
                END $$
                """.formatted(EMBEDDING_DIMS));

        jdbcTemplate.execute("""
                ALTER TABLE image_embedding
                    ADD COLUMN IF NOT EXISTS embedding vector(%d)
                """.formatted(EMBEDDING_DIMS));

        log.info("[pgvector] Extension and vector({}) column ensured on image_embedding", EMBEDDING_DIMS);
    }
}
