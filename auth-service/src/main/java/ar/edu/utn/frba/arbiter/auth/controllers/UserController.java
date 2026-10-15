package ar.edu.utn.frba.arbiter.auth.controllers;

import ar.edu.utn.frba.arbiter.auth.dto.AnalystResponse;
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
                    El referente de una aseguradora invita a un nuevo usuario, que queda
                    "pendiente" hasta que elige su propia contraseña por mail (Auth0). Queda
                    vinculado a la misma aseguradora del referente que lo invita.

                    Solo admite rol ANALISTA_SINIESTROS. Los asegurados no se dan de alta acá:
                    su identidad es dato de la compañía, así que van por el alta masiva
                    (POST /insured/bulk-provision).
                    """)
    public ResponseEntity<UserResponse> createUser(
            @RequestBody @Valid CreateUserRequest request, Authentication authentication) {
        UserResponse response = userService.createUser(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/insured/bulk-provision")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Dar de alta usuarios (asegurados, en bloque)",
            description = """
                    Lee los asegurados con póliza vigente de la BD de la aseguradora del referente
                    y les crea la cuenta en la plataforma, invitándolos por mail a elegir su
                    contraseña. Desde ahí siguen el onboarding de primer ingreso (H0009).

                    Es idempotente: a quien ya tiene cuenta no se la duplica — se lo reconoce por
                    email y solo se le vincula esta aseguradora, que es lo que le suma sus pólizas.
                    Los mails salen de a poco para no disparar un envío masivo de una.

                    Responde 202: la corrida sigue en segundo plano y su resultado queda en el log.
                    """)
    public ResponseEntity<Void> provisionInsuredAccounts(Authentication authentication) {
        userService.provisionInsuredAccounts(authentication.getName());
        return ResponseEntity.accepted().build();
    }

    @GetMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Listar usuarios",
            description = "Devuelve los usuarios de la aseguradora del referente, con su rol actual, más recientes primero.")
    public ResponseEntity<List<UserResponse>> listUsers(Authentication authentication) {
        return ResponseEntity.ok(userService.listUsers(authentication.getName()));
    }

    @GetMapping("/analysts")
    @PreAuthorize("hasAnyRole('ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA')")
    @Operation(summary = "Listar analistas asignables",
            description = """
                    Devuelve los analistas de la aseguradora del request, ordenados por apellido.
                    Alimenta el selector de asignación de expedientes de la bandeja, por eso —a
                    diferencia del listado completo de usuarios— también lo puede consultar un
                    analista: asignar es una acción de ambos roles, no solo del referente.

                    El `id` es el de `claims_analyst` (el que espera `POST /cases/{id}/assign`),
                    no el de `users`. Al ser una tabla por esquema, el listado ya viene acotado a
                    una sola aseguradora: el aislamiento lo da el tenant resuelto para el request,
                    no un filtro.
                    """)
    public ResponseEntity<List<AnalystResponse>> listAnalysts() {
        return ResponseEntity.ok(userService.listAssignableAnalysts());
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Cambiar el rol de un usuario",
            description = """
                    El referente cambia el rol entre Analista de siniestros y Referente de
                    aseguradora, y se crea el perfil del rol nuevo. No aplica a los asegurados ni
                    al propio referente (400). Responde 404 si el usuario no es de su aseguradora.
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
                    Responde 404 si el usuario no es de la aseguradora del referente.
                    """)
    public ResponseEntity<UserResponse> resendInvite(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(userService.resendInvite(id, authentication.getName()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Eliminar un usuario",
            description = """
                    Quita al usuario de la aseguradora del referente. Si no pertenece a otra
                    aseguradora, borra la cuenta de forma definitiva, incluida la de Auth0. Un
                    asegurado de varias aseguradoras conserva su cuenta y el acceso a las demás.

                    Responde 409 si el usuario tiene actividad registrada (historial, mensajes,
                    liquidaciones, reglas creadas): en ese caso no cambia nada. Responde 404 si el
                    usuario no es de la aseguradora del referente. El referente no puede eliminar
                    su propia cuenta.
                    """)
    public ResponseEntity<Void> deleteUser(@PathVariable Long id, Authentication authentication) {
        userService.deleteUser(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
