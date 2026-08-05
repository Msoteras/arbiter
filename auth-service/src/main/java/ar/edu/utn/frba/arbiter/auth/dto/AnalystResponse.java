package ar.edu.utn.frba.arbiter.auth.dto;

/**
 * Un analista al que se le puede asignar un expediente. Es un read model aparte de
 * {@code UserResponse} porque responde otra pregunta: no "qué cuentas hay en la plataforma"
 * (identidad, estado, rol, del esquema común) sino "a quién de esta aseguradora le puedo dar
 * este expediente".
 *
 * <p>El {@code id} es el de {@code claims_analyst}, no el de {@code users}: es el que va en
 * {@code cases.analyst_id}. Al ser una tabla por esquema, <b>solo tiene sentido dentro de la
 * aseguradora que lo devolvió</b> — no lo compares entre tenants.
 */
public record AnalystResponse(
        Long id,
        String nombre,
        String apellido,
        String email
) {}
