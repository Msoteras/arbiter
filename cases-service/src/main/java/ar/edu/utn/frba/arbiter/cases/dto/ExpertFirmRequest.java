package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Alta o edición de un perito del catálogo, por el referente.
 *
 * @param branchId el ramo en el que se especializa, o null para un perito que cubre todos. Null no
 *                 es "no sé": es "generalista", y el analista necesita ver la diferencia al elegir.
 * @param active   desactivar es cómo se saca un perito de circulación sin borrar las derivaciones
 *                 que ya se le hicieron.
 */
public record ExpertFirmRequest(
        @NotBlank(message = "name is required") String name,
        // El mail es el único canal con el perito: uno inválido deja el expediente esperando a
        // alguien que nunca se enteró.
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address") String email,
        String zone,
        Long branchId,
        boolean active
) {}
