package ar.edu.utn.frba.arbiter.auth.controllers;

import ar.edu.utn.frba.arbiter.auth.dto.ActivateAccountRequest;
import ar.edu.utn.frba.arbiter.auth.dto.ForgotPasswordRequest;
import ar.edu.utn.frba.arbiter.auth.dto.LoginRequest;
import ar.edu.utn.frba.arbiter.auth.dto.LoginResponse;
import ar.edu.utn.frba.arbiter.auth.dto.PublicKeyResponse;
import ar.edu.utn.frba.arbiter.auth.dto.ResetPasswordRequest;
import ar.edu.utn.frba.arbiter.auth.services.AuthService;
import ar.edu.utn.frba.arbiter.auth.services.PasswordCipher;
import ar.edu.utn.frba.arbiter.auth.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Login and session issuance")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final PasswordCipher passwordCipher;

    @GetMapping("/public-key")
    @Operation(summary = "Clave pública para cifrar la contraseña del login",
            description = """
                    Endpoint público. La clave RSA con la que el navegador cifra la contraseña antes
                    de mandarla a `/login`, para que no viaje legible en el body.
                    """)
    public ResponseEntity<PublicKeyResponse> publicKey() {
        return ResponseEntity.ok(new PublicKeyResponse(passwordCipher.publicKeyBase64(), "RSA-OAEP-256"));
    }

    @PostMapping("/login")
    @Operation(summary = "Login",
            description = """
                    Valida email + contraseña contra Auth0 y devuelve un JWT propio con el rol,
                    nombre y aseguradora del usuario. Bloquea la cuenta 15 minutos tras 5 intentos
                    fallidos consecutivos (contador local, Auth0 no lo ve).

                    `password` NO es la contraseña: es el sobre cifrado `ARB1.<base64>` que se arma
                    con la clave de `/public-key`. Adentro va `<epochMillis>:<contraseña>`, y el
                    sobre vale 5 minutos.
                    """)
    public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/activate")
    @Operation(summary = "Activar cuenta y definir contraseña",
            description = """
                    Endpoint público (sin JWT) al que llega el usuario invitado desde el link del
                    mail. Valida el token de invitación, define la contraseña elegida y recién ahí
                    lo provisiona en Auth0 (Fase 3 — ver CLAUDE.md, decisión #8).

                    `password` viaja cifrada, igual que en `/login`: es el sobre `ARB1.<base64>`
                    armado con la clave de `/public-key`.
                    """)
    public ResponseEntity<Void> activate(@RequestBody @Valid ActivateAccountRequest request) {
        userService.activateAccount(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invite-tokens/{token}")
    @Operation(summary = "Validar un token de invitación/reset",
            description = """
                    Endpoint público de solo lectura — no consume el token. Lo usa el frontend
                    antes de mostrar el formulario de contraseña (activación o reset), para no
                    dejar ver esa pantalla con un token inventado o vencido en la URL.
                    """)
    public ResponseEntity<Void> checkToken(@PathVariable String token) {
        userService.checkToken(token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Pedir reset de contraseña",
            description = """
                    Endpoint público. Si el email existe, manda un mail con un link de un solo uso
                    (2hs de validez). Responde 204 siempre, exista o no el email — no hay que
                    filtrar qué direcciones están registradas.
                    """)
    public ResponseEntity<Void> forgotPassword(@RequestBody @Valid ForgotPasswordRequest request) {
        userService.requestPasswordReset(request.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Confirmar el reset y definir la contraseña nueva",
            description = """
                    Endpoint público al que llega el usuario desde el link de "olvidé mi
                    contraseña". Valida el token, actualiza la contraseña en Auth0 primero y recién
                    ahí commitea el cambio local.

                    `password` viaja cifrada, igual que en `/login`: es el sobre `ARB1.<base64>`
                    armado con la clave de `/public-key`.
                    """)
    public ResponseEntity<Void> resetPassword(@RequestBody @Valid ResetPasswordRequest request) {
        userService.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }
}
