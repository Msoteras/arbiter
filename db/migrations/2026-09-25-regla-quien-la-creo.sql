-- =============================================================================
-- 2026-09-25 · Quién creó cada regla y la configuración del puntaje
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Agrega, NOT NULL:
--   · <tenant>.insurer_rule          → created_by, el usuario que creó la regla.
--   · <tenant>.scoring_configuration → created_by, ídem para la configuración del puntaje.
--
-- Apunta a arbiter_common.users y no a insurer_referent a propósito: el autor es
-- siempre un usuario, sin atarlo a qué rol puede configurar reglas hoy.
--
-- Completa las filas existentes con el usuario del referente que hizo el primer
-- cambio registrado de esa regla; si nunca se modificó (incluidas las que cargó
-- el script de datos), con el del primer referente de la aseguradora, que es
-- quien se considera que la cargó. Recién después pasa la columna a NOT NULL: si
-- una aseguradora no tuviera referente, falla ahí y no deja la columna a medias.
--
-- IMPORTANTE: los servicios corren con ddl-auto=validate. Aplicar ANTES de
-- desplegar el código que declara el campo, o rules-service no levanta.
--
-- DESPUÉS de esta migración, reemplazar arbiter_common.create_tenant_schema por
-- la de db/init-multitenant.sql, que ahora recibe p_created_by: borrar la de un
-- parámetro (DROP FUNCTION arbiter_common.create_tenant_schema(TEXT)) y correr el
-- bloque CREATE OR REPLACE FUNCTION ... $fn$ LANGUAGE plpgsql; tal cual está en el
-- init. No se copia acá para no tener dos versiones de la función que mantener.
-- (En Railway ya se hizo el 25/09/2026.)
--
-- Idempotente: se puede correr más de una vez sin romper nada (solo completa
-- las filas que siguen en NULL).
-- =============================================================================

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format(
            'ALTER TABLE %I.insurer_rule
                ADD COLUMN IF NOT EXISTS created_by BIGINT REFERENCES arbiter_common.users(id)',
            tenant);

        EXECUTE format(
            'ALTER TABLE %I.scoring_configuration
                ADD COLUMN IF NOT EXISTS created_by BIGINT REFERENCES arbiter_common.users(id)',
            tenant);

        EXECUTE format($sql$
            UPDATE %1$I.insurer_rule r
               SET created_by = COALESCE(
                       (SELECT ir.user_id
                          FROM %1$I.insurer_rule_history h
                          JOIN %1$I.insurer_referent ir ON ir.id = h.changed_by
                         WHERE h.rule_id = r.id
                         ORDER BY h.valid_from, h.id LIMIT 1),
                       (SELECT ir.user_id FROM %1$I.insurer_referent ir ORDER BY ir.id LIMIT 1))
             WHERE r.created_by IS NULL
            $sql$, tenant);

        EXECUTE format($sql$
            UPDATE %1$I.scoring_configuration c
               SET created_by = COALESCE(
                       (SELECT ir.user_id
                          FROM %1$I.scoring_configuration_history h
                          JOIN %1$I.insurer_referent ir ON ir.id = h.changed_by
                         WHERE h.scoring_configuration_id = c.id
                         ORDER BY h.valid_from, h.id LIMIT 1),
                       (SELECT ir.user_id FROM %1$I.insurer_referent ir ORDER BY ir.id LIMIT 1))
             WHERE c.created_by IS NULL
            $sql$, tenant);

        EXECUTE format('ALTER TABLE %I.insurer_rule ALTER COLUMN created_by SET NOT NULL', tenant);
        EXECUTE format('ALTER TABLE %I.scoring_configuration ALTER COLUMN created_by SET NOT NULL', tenant);

    END LOOP;
END $$;

COMMIT;

-- Verificación: a quién quedó atribuida cada regla, por aseguradora.
DO $$
DECLARE
    tenant TEXT;
    row RECORD;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        FOR row IN EXECUTE format(
                'SELECT u.email, count(*) AS rules
                   FROM %I.insurer_rule r JOIN arbiter_common.users u ON u.id = r.created_by
                  GROUP BY u.email ORDER BY u.email', tenant) LOOP
            RAISE NOTICE '% · % regla(s) creadas por %', tenant, row.rules, row.email;
        END LOOP;
    END LOOP;
END $$;
