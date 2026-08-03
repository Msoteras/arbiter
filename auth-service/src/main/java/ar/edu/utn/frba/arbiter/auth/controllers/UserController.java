package ar.edu.utn.frba.arbiter.auth.controllers;

import ar.edu.utn.frba.arbiter.auth.dto.CreateUserRequest;
import ar.edu.utn.frba.arbiter.auth.dto.UpdateUserRoleRequest;
import ar.edu.utn.frba.arbiter.auth.dto.UserResponse;
import ar.edu.utn.frba.arbiter.auth.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Alta, listado y gestión de roles de usuarios (H0002 / H0003)")
public class UserController {

    private final UserService userService;

    @PostMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Dar de alta un usuario",
            description = """
                    El referente de una aseguradora da de alta un nuevo usuario con contraseña
                    directa (paso transitorio, ver CLAUDE.md decisión #8). Por ahora solo admite
                    rol ANALISTA_SINIESTROS — el asegurado no se da de alta por acá.
                    """)
    public ResponseEntity<UserResponse> createUser(@RequestBody @Valid CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Listar usuarios",
            description = "Devuelve todos los usuarios del sistema con su rol actual, más recientes primero.")
    public ResponseEntity<List<UserResponse>> listUsers() {
        return ResponseEntity.ok(userService.listUsers());
    }

    @GetMapping("/analysts")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Listar analistas asignables",
            description = """
                    Devuelve los usuarios con rol ANALISTA_SINIESTROS, ordenados por apellido.
                    Alimenta el selector de asignación de expedientes de la bandeja, por eso —a
                    diferencia del listado completo de usuarios— también lo puede consultar un
                    analista: asignar es una acción de ambos roles, no solo del referente.

                    Incluye los analistas en estado PENDING (invitados sin activar): quien asigna
                    ve el estado y decide. Todavía no filtra por aseguradora — ese recorte llega
                    con el esquema multi-tenant (ver GAPS-FLUJO.md, Gap F).
                    """)
    public ResponseEntity<List<UserResponse>> listAnalysts() {
        return ResponseEntity.ok(userService.listAssignableAnalysts());
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Cambiar el rol de un usuario",
            description = """
                    El referente puede reasignar el rol de cualquier usuario, incluso promover a
                    otro referente (no es una escalada real: ya tiene acceso completo). No puede
                    cambiar su propio rol.
                    """)
    public ResponseEntity<UserResponse> updateRole(
            @PathVariable Long id,
            @RequestBody @Valid UpdateUserRoleRequest request,
            Authentication authentication
    ) {
        UserResponse response = userService.updateRole(id, request.rol(), authentication.getName());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/resend-invite")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Reenviar la invitación a un usuario pendiente",
            description = """
                    Genera un token de invitación nuevo (48hs) y reenvía el mail de activación.
                    Solo tiene sentido para usuarios en estado PENDING — 400 si ya está activo.
                    """)
    public ResponseEntity<UserResponse> resendInvite(@PathVariable Long id) {
        return ResponseEntity.ok(userService.resendInvite(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Eliminar un usuario",
            description = """
                    Borrado definitivo e irreversible (no es una baja/desactivación — no existe ese
                    estado hoy). El referente no puede eliminar su propia cuenta.
                    """)
    public ResponseEntity<Void> deleteUser(@PathVariable Long id, Authentication authentication) {
        userService.deleteUser(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
