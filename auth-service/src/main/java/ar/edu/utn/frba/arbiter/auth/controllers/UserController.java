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
}
