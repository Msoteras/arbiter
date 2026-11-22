-- 2026-11-21 · Sofía Montenegro (final presentation, case 1) gets her own mailbox.
-- 2026-11-18-asegurados-demo-fast-track.sql loaded her with an alias of the team's inbox. The demo
-- opens her Gmail in its own Chrome profile, so it needs a mailbox with nothing else in it.
-- Insurer database only: the bulk provisioning reads the email from here and sends the invitation.
-- Run it before the referent's "Dar de alta asegurados". Keep in sync with perfiles.js.
-- Idempotent: looked up by DNI.

BEGIN;

UPDATE aseguradora_bbva.asegurado
   SET email = 'sofiamontenegro.1899@gmail.com'
 WHERE documento = '40.218.736'
   AND email <> 'sofiamontenegro.1899@gmail.com';

COMMIT;
