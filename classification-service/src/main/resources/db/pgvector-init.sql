-- pgvector initialization for duplicate image detection.
-- Run once per schema after enabling the extension.
-- Hibernate ddl-auto creates the base columns; this adds the vector column and index.

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE image_embedding
    ADD COLUMN IF NOT EXISTS embedding vector(512); -- CLIP ViT-B-32

-- IVFFlat index for cosine distance — requires at least ~100 rows to be effective.
-- For small datasets the sequential scan is fine; the index kicks in at scale.
CREATE INDEX IF NOT EXISTS idx_image_embedding_cosine
    ON image_embedding
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
