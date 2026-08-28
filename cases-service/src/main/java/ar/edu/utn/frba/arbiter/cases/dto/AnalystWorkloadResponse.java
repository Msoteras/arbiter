package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Carga de trabajo de un analista del tenant: cuántos expedientes activos (no resueltos) tiene
 * asignados. Alimenta el panel "Carga del equipo" del inicio del referente, para repartir trabajo
 * de un vistazo.
 *
 * <p>{@code activeCases} cuenta solo expedientes en curso (excluye APPROVED/REJECTED): la carga es
 * lo que todavía requiere trabajo, no el histórico. Un analista sin expedientes asignados aparece
 * igual, con cero — el panel muestra a todo el equipo, no solo a los ocupados.
 *
 * @param analystId   id de {@code claims_analyst}, local al esquema de la aseguradora
 * @param name        nombre y apellido del analista, ya resuelto (para mostrar)
 * @param activeCases cantidad de expedientes activos asignados
 */
public record AnalystWorkloadResponse(Long analystId, String name, long activeCases) {
}
