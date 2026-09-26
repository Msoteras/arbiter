-- 2026-09-21 · unaccent, called unqualified by the inbox free-text search (CaseSpecifications.freeText).
-- PR #96 only added it to init-multitenant.sql, so older databases answered 500 on every search. In
-- public, like vector, so every tenant schema reaches it. Applied on Railway on 25/09/2026. Idempotent.

CREATE EXTENSION IF NOT EXISTS unaccent SCHEMA public;
